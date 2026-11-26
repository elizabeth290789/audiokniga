package com.audiokniga.app;

import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.hpsf.SummaryInformation;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.HWPFDocumentCore;
import org.apache.poi.hwpf.HWPFOldDocument;
import org.apache.poi.hwpf.OldWordFileFormatException;
import org.apache.poi.hwpf.model.StyleDescription;
import org.apache.poi.hwpf.model.StyleSheet;
import org.apache.poi.hwpf.usermodel.CharacterRun;
import org.apache.poi.hwpf.usermodel.Paragraph;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.regex.Pattern;

final class LegacyDocParser {
    private static final int FILE_LIMIT = 16 * 1024 * 1024;
    private static final int FIELD_DEPTH_LIMIT = 128;
    private static final int STYLE_DEPTH_LIMIT = 32;
    private static final Pattern FIGURE_SEQUENCE = Pattern.compile(
            "\\x13\\s*SEQ\\s+\"?(?:figure|fig|illustration|рисунок|рис\\.?|иллюстрация)(?=[\\s\"\\\\\\x14\\x15]|$)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern STYLE_SEPARATORS = Pattern.compile("[\\s_-]+");

    private LegacyDocParser() {}

    static BookParser.ParsedBook parse(InputStream input) throws IOException {
        try (POIFSFileSystem file = new POIFSFileSystem(new LimitedInput(input))) {
            HWPFDocumentCore document = open(file);
            Range body = document.getRange();
            StyleSheet styles = document.getStyleSheet();
            StringBuilder text = new StringBuilder();
            FieldText fields = new FieldText();
            int paragraphs = body.numParagraphs();
            for (int i = 0; i < paragraphs; i++) {
                Paragraph paragraph = body.getParagraph(i);
                boolean caption = hasCaptionStyle(styles, paragraph.getStyleIndex())
                        || FIGURE_SEQUENCE.matcher(paragraph.text()).find();
                StringBuilder visible = new StringBuilder();
                int runs = paragraph.numCharacterRuns();
                if (runs == 0) {
                    fields.append(paragraph.text(), !caption, visible);
                }
                for (int j = 0; j < runs; j++) {
                    CharacterRun run = paragraph.getCharacterRun(j);
                    if (run != null) {
                        boolean readable = !caption && !run.isVanished() && !run.isMarkedDeleted();
                        fields.append(run.text(), readable, visible);
                    }
                }
                String paragraphText = visible.toString().trim();
                if (!paragraphText.isEmpty()) {
                    if ((long) text.length() + paragraphText.length() + 2 > FILE_LIMIT) {
                        throw new LimitExceeded();
                    }
                    if (text.length() > 0) {
                        text.append("\n\n");
                    }
                    text.append(paragraphText);
                }
            }
            return new BookParser.ParsedBook(readTitle(document), text.toString());
        } catch (EncryptedDocumentException exception) {
            throw new IOException("Документ DOC защищён паролем. Откройте копию без пароля.", exception);
        } catch (LimitExceeded exception) {
            throw new IOException("Документ DOC слишком большой для импорта.", exception);
        } catch (IOException | RuntimeException exception) {
            throw new IOException("Не удалось прочитать DOC. Проверьте, что это документ Microsoft Word.", exception);
        }
    }

    private static HWPFDocumentCore open(POIFSFileSystem file) throws IOException {
        try {
            return new HWPFDocument(file);
        } catch (OldWordFileFormatException exception) {
            return new HWPFOldDocument(file);
        }
    }

    private static String readTitle(HWPFDocumentCore document) {
        try {
            SummaryInformation information = document.getSummaryInformation();
            String title = information == null ? null : information.getTitle();
            return title == null ? "" : title.trim();
        } catch (RuntimeException exception) {
            // A damaged optional properties stream should not discard the document text.
            return "";
        }
    }

    private static boolean hasCaptionStyle(StyleSheet styles, int index) {
        for (int depth = 0; styles != null && index >= 0 && depth < STYLE_DEPTH_LIMIT; depth++) {
            StyleDescription style;
            try {
                style = styles.getStyleDescription(index);
            } catch (IndexOutOfBoundsException exception) {
                return false;
            }
            if (style == null) {
                return false;
            }
            String name = style.getName();
            if (name != null) {
                String key = STYLE_SEPARATORS.matcher(name.toLowerCase(Locale.ROOT)).replaceAll("");
                switch (key) {
                    case "caption":
                    case "figurecaption":
                    case "imagecaption":
                    case "illustrationcaption":
                    case "подпись":
                    case "подписькрисунку":
                    case "подписькиллюстрации":
                        return true;
                    default:
                        break;
                }
            }
            int base = style.getBaseStyle();
            if (base == index) {
                return false;
            }
            index = base;
        }
        return false;
    }

    private static final class FieldText {
        private final ArrayDeque<Boolean> instructions = new ArrayDeque<>();
        private int hiddenDepth;

        void append(String source, boolean readable, StringBuilder text) throws IOException {
            for (int i = 0; i < source.length(); i++) {
                char ch = source.charAt(i);
                switch (ch) {
                    case '\u0013':
                        if (instructions.size() == FIELD_DEPTH_LIMIT) {
                            throw new IOException("DOC содержит слишком много вложенных полей.");
                        }
                        instructions.push(true);
                        hiddenDepth++;
                        break;
                    case '\u0014':
                        if (!instructions.isEmpty() && instructions.peek()) {
                            instructions.pop();
                            instructions.push(false);
                            hiddenDepth--;
                        }
                        break;
                    case '\u0015':
                        if (!instructions.isEmpty() && instructions.pop()) {
                            hiddenDepth--;
                        }
                        break;
                    default:
                        if (readable && hiddenDepth == 0) {
                            appendCharacter(ch, text);
                        }
                        break;
                }
            }
        }

        private static void appendCharacter(char ch, StringBuilder text) {
            switch (ch) {
                case '\t':
                    text.append(' ');
                    break;
                case '\n':
                case '\r':
                case '\u0007':
                case '\u000b':
                case '\u000c':
                    text.append('\n');
                    break;
                case '\u001e':
                    text.append('-');
                    break;
                default:
                    if (!Character.isISOControl(ch) && ch != '\ufffc' && ch != '\u00ad') {
                        text.append(ch);
                    }
                    break;
            }
        }
    }

    private static final class LimitedInput extends FilterInputStream {
        private int remaining = FILE_LIMIT;

        LimitedInput(InputStream input) {
            super(input);
        }

        @Override public int read() throws IOException {
            int value = in.read();
            if (value >= 0 && remaining-- == 0) {
                throw new LimitExceeded();
            }
            return value;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = in.read(bytes, offset, Math.min(length, remaining + 1));
            if (count > remaining) {
                throw new LimitExceeded();
            }
            if (count > 0) {
                remaining -= count;
            }
            return count;
        }

        @Override public void close() {
            // The caller owns the input stream.
        }
    }

    private static final class LimitExceeded extends IOException {}
}
