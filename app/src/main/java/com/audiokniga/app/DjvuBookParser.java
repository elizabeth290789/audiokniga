package com.audiokniga.app;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Extracts the hidden text stream from single-page or bundled DjVu documents. */
final class DjvuBookParser {
    private static final int MAGIC = 0x41542654; // AT&T
    private static final int FORM = 0x464f524d;
    private static final int DJVU = 0x444a5655;
    private static final int DJVM = 0x444a564d;
    private static final int DIRM = 0x4449524d;
    private static final int TEXT_RAW = 0x54585461; // TXTa
    private static final int TEXT_COMPRESSED = 0x5458547a; // TXTz
    private static final int FILE_LIMIT = 512 * 1024 * 1024;
    private static final int TEXT_LIMIT = 16 * 1024 * 1024;
    private static final int MAX_ENTRIES = 20_000;
    private static final int MAX_CHUNKS = 200_000;

    private DjvuBookParser() {}

    static BookParser.ParsedBook parse(InputStream input) throws IOException {
        DataInputStream stream = new DataInputStream(new BufferedInputStream(input));
        try {
            int header = stream.readInt();
            int formStart = 0;
            if (header == MAGIC) {
                header = stream.readInt();
                formStart = 4;
            }
            if (header != FORM) {
                throw malformed();
            }
            long size = Integer.toUnsignedLong(stream.readInt());
            if (size > FILE_LIMIT) {
                throw tooLarge();
            }
            Part document = new Part(stream, (int) size);
            int type = new DataInputStream(document).readInt();
            byte[] scratch = new byte[8192];
            String text;
            if (type == DJVU) {
                text = readPage(document, scratch);
            } else if (type == DJVM) {
                text = readBundle(document, formStart + 8, scratch);
            } else {
                throw malformed();
            }
            if (text.isEmpty()) {
                throw new IOException("В DjVu нет текстового слоя. Распознавание сканов пока недоступно.");
            }
            return new BookParser.ParsedBook("", text);
        } catch (EOFException | CharacterCodingException exception) {
            throw new IOException("Не удалось прочитать структуру DjVu.", exception);
        }
    }

    private static String readBundle(Part document, int payloadOffset, byte[] scratch) throws IOException {
        long[] pageOrder = null;
        Map<Long, String> pages = new HashMap<>();
        int textLength = 0;
        int chunkCount = 0;
        while (document.remaining > 0) {
            if (++chunkCount > MAX_CHUNKS) {
                throw tooLarge();
            }
            long offset = payloadOffset + document.total - (long) document.remaining;
            Chunk chunk = nextChunk(document);
            if (chunk.tag == DIRM) {
                if (pageOrder != null) {
                    throw malformed();
                }
                pageOrder = readDirectory(chunk.content);
            } else if (chunk.tag == FORM) {
                int type = new DataInputStream(chunk.content).readInt();
                if (type == DJVU) {
                    if (pages.size() >= MAX_ENTRIES) {
                        throw tooLarge();
                    }
                    String page = readPage(chunk.content, scratch);
                    textLength += page.length();
                    if (textLength > TEXT_LIMIT) {
                        throw tooLarge();
                    }
                    pages.put(offset, page);
                }
            }
            finishChunk(document, chunk.content, scratch);
        }
        if (pageOrder == null) {
            throw malformed();
        }
        StringBuilder text = new StringBuilder(textLength);
        for (long offset : pageOrder) {
            String page = pages.remove(offset);
            if (page == null) {
                throw malformed();
            }
            if (page.isEmpty()) {
                continue;
            }
            if (text.length() > 0) {
                text.append("\n\n");
            }
            text.append(page);
            if (text.length() > TEXT_LIMIT) {
                throw tooLarge();
            }
        }
        return text.toString();
    }

    private static long[] readDirectory(Part chunk) throws IOException {
        DataInputStream directory = new DataInputStream(chunk);
        int flags = directory.readUnsignedByte();
        int version = flags & 0x7f;
        if (version > 1) {
            throw new IOException("Эта версия структуры DjVu пока не поддерживается.");
        }
        int count = directory.readUnsignedShort();
        if (count > MAX_ENTRIES) {
            throw tooLarge();
        }
        if ((flags & 0x80) == 0) {
            throw new IOException("Страницы этого DjVu хранятся в отдельных файлах. Нужен единый файл DjVu.");
        }
        long[] offsets = new long[count];
        for (int entry = 0; entry < count; entry++) {
            offsets[entry] = Integer.toUnsignedLong(directory.readInt());
            if (version == 0) {
                readInt24(directory);
            }
        }
        if (count == 0) {
            return offsets;
        }
        DataInputStream entries = new DataInputStream(new DjvuBzzInputStream(directory));
        if (version == 1) {
            for (int entry = 0; entry < count; entry++) {
                readInt24(entries);
            }
        }
        int pages = 0;
        for (long offset : offsets) {
            int fileFlags = entries.readUnsignedByte();
            boolean page = version == 0 ? (fileFlags & 1) != 0 : (fileFlags & 0x3f) == 1;
            if (page) {
                offsets[pages++] = offset;
            }
        }
        return Arrays.copyOf(offsets, pages);
    }

    private static String readPage(Part page, byte[] scratch) throws IOException {
        String text = "";
        boolean hasLayer = false;
        int chunkCount = 0;
        while (page.remaining > 0) {
            if (++chunkCount > MAX_CHUNKS) {
                throw tooLarge();
            }
            Chunk chunk = nextChunk(page);
            if (chunk.tag == TEXT_RAW || chunk.tag == TEXT_COMPRESSED) {
                if (hasLayer) {
                    throw malformed();
                }
                hasLayer = true;
                InputStream layer = chunk.tag == TEXT_COMPRESSED
                        ? new DjvuBzzInputStream(chunk.content) : chunk.content;
                DataInputStream data = new DataInputStream(layer);
                int textSize = readInt24(data);
                if (chunk.tag == TEXT_RAW && textSize > chunk.content.remaining) {
                    throw malformed();
                }
                byte[] bytes = new byte[textSize];
                data.readFully(bytes);
                String decoded = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
                text = normalize(decoded);
                if (text.length() > TEXT_LIMIT) {
                    throw tooLarge();
                }
            }
            finishChunk(page, chunk.content, scratch);
        }
        return text;
    }

    private static String normalize(String raw) {
        StringBuilder clean = new StringBuilder(raw.length());
        int lineBreaks = 0;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '\r' && i + 1 < raw.length() && raw.charAt(i + 1) == '\n') {
                continue;
            }
            if (ch == '\r' || ch == '\n') {
                lineBreaks = Math.min(2, lineBreaks + 1);
            } else if (ch == '\u000b' || ch == '\f' || ch == '\u001d' || ch == '\u001e' || ch == '\u001f') {
                lineBreaks = 2;
            } else if (ch == '\t' || !Character.isISOControl(ch)) {
                if (lineBreaks > 0 && Character.isWhitespace(ch)) {
                    continue;
                }
                if (clean.length() > 0) {
                    for (int line = 0; line < lineBreaks; line++) {
                        clean.append('\n');
                    }
                }
                lineBreaks = 0;
                clean.append(ch == '\t' ? ' ' : ch);
            }
        }
        return clean.toString().trim();
    }

    private static Chunk nextChunk(Part container) throws IOException {
        DataInputStream header = new DataInputStream(container);
        int tag = header.readInt();
        long size = Integer.toUnsignedLong(header.readInt());
        if (size > container.remaining) {
            throw malformed();
        }
        return new Chunk(tag, new Part(container, (int) size));
    }

    private static void finishChunk(Part container, Part chunk, byte[] scratch) throws IOException {
        while (chunk.remaining > 0) {
            chunk.read(scratch, 0, Math.min(scratch.length, chunk.remaining));
        }
        if ((chunk.total & 1) != 0 && container.remaining > 0) {
            container.read();
        }
    }

    private static int readInt24(DataInputStream input) throws IOException {
        return (input.readUnsignedByte() << 16) | input.readUnsignedShort();
    }

    private static IOException malformed() {
        return new IOException("Не удалось прочитать структуру DjVu.");
    }

    private static IOException tooLarge() {
        return new IOException("Файл DjVu слишком большой для импорта.");
    }

    private static final class Chunk {
        final int tag;
        final Part content;

        Chunk(int tag, Part content) {
            this.tag = tag;
            this.content = content;
        }
    }

    private static final class Part extends InputStream {
        final InputStream source;
        final int total;
        int remaining;

        Part(InputStream source, int length) {
            this.source = source;
            total = length;
            remaining = length;
        }

        @Override
        public int read() throws IOException {
            if (remaining == 0) {
                return -1;
            }
            int value = source.read();
            if (value < 0) {
                throw new EOFException();
            }
            remaining--;
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (remaining == 0) {
                return -1;
            }
            int count = source.read(bytes, offset, Math.min(length, remaining));
            if (count < 0) {
                throw new EOFException();
            }
            if (count == 0) {
                bytes[offset] = (byte) read();
                return 1;
            }
            remaining -= count;
            return count;
        }
    }
}
