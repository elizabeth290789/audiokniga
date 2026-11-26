package com.audiokniga.app;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/** Extracts the main document from Word and OpenDocument ZIP packages. */
final class OfficeXmlParser {
    private static final int DOCUMENT_LIMIT = 16 * 1024 * 1024;
    private static final int AUXILIARY_LIMIT = 4 * 1024 * 1024;
    private static final long ARCHIVE_LIMIT = 256L * 1024 * 1024;
    private static final int ENTRY_LIMIT = 10_000;
    private static final int MAX_XML_DEPTH = 256;

    private static final String WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String WORD_STRICT = "http://purl.oclc.org/ooxml/wordprocessingml/main";
    private static final String ODF_OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0";
    private static final String ODF_STYLE = "urn:oasis:names:tc:opendocument:xmlns:style:1.0";
    private static final String ODF_TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0";
    private static final String ODF_DRAW = "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0";
    private static final String DC = "http://purl.org/dc/elements/1.1/";
    private static final Pattern SEQUENCE = Pattern.compile(
            "(?:^|\\s)SEQ\\s+(?:\"([^\"]+)\"|([^\\s\\\\]+))", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_SEPARATOR = Pattern.compile("_20_|[ _-]");

    private OfficeXmlParser() {}

    static BookParser.ParsedBook parse(InputStream input, String extension) throws IOException {
        boolean word = "docx".equals(extension);
        String mainPath = word ? "word/document.xml" : "content.xml";
        String stylesPath = word ? "word/styles.xml" : "styles.xml";
        String metaPath = word ? "docProps/core.xml" : "meta.xml";
        Map<String, byte[]> parts = readParts(input, mainPath, stylesPath, metaPath);
        byte[] document = parts.get(mainPath);
        if (document == null) {
            throw new IOException("В документе отсутствует основная текстовая часть.");
        }

        Styles styles = new Styles(word);
        if (parts.containsKey(stylesPath)) {
            try {
                BookParser.parseOfficeXml(parts.get(stylesPath), styles);
            } catch (IOException ignored) {
                styles = new Styles(word);
            }
        }
        Body body = new Body(word, styles);
        BookParser.parseOfficeXml(document, body);

        String title = "";
        if (parts.containsKey(metaPath)) {
            Title metadata = new Title();
            try {
                BookParser.parseOfficeXml(parts.get(metaPath), metadata);
                title = metadata.value.toString().trim();
            } catch (IOException ignored) {
                // The readable document can still be imported with a filename title.
            }
        }
        return new BookParser.ParsedBook(title, body.output.value.toString());
    }

    private static Map<String, byte[]> readParts(InputStream input, String main, String styles, String meta)
            throws IOException {
        Map<String, byte[]> parts = new HashMap<>();
        byte[] buffer = new byte[8192];
        long expanded = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ArchiveInput(input))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > ENTRY_LIMIT) {
                    throw tooLarge();
                }
                String path = entry.getName();
                boolean wanted = !entry.isDirectory() && (path.equals(main) || path.equals(styles) || path.equals(meta));
                int limit = path.equals(main) ? DOCUMENT_LIMIT : AUXILIARY_LIMIT;
                ByteArrayOutputStream data = wanted ? new ByteArrayOutputStream() : null;
                int count;
                while ((count = zip.read(buffer)) != -1) {
                    expanded += count;
                    if (expanded > ARCHIVE_LIMIT) {
                        throw tooLarge();
                    }
                    if (data != null) {
                        if (data.size() + count > limit) {
                            throw tooLarge();
                        }
                        data.write(buffer, 0, count);
                    }
                }
                if (data != null && parts.put(path, data.toByteArray()) != null) {
                    throw new IOException("В документе повторяются внутренние файлы.");
                }
            }
        } catch (ZipException e) {
            throw new IOException("Не удалось открыть архив документа. Возможно, файл повреждён или защищён.", e);
        }
        return parts;
    }

    private static IOException tooLarge() {
        return new IOException("Документ слишком большой для импорта.");
    }

    private static final class ArchiveInput extends FilterInputStream {
        private long count;

        ArchiveInput(InputStream input) { super(input); }

        @Override public int read() throws IOException {
            int value = in.read();
            if (value >= 0 && ++count > ARCHIVE_LIMIT) {
                throw tooLarge();
            }
            return value;
        }

        @Override public int read(byte[] bytes, int start, int length) throws IOException {
            int read = in.read(bytes, start, length);
            if (read > 0 && (count += read) > ARCHIVE_LIMIT) {
                throw tooLarge();
            }
            return read;
        }

        @Override public void close() {
            // The caller owns the underlying stream.
        }
    }

    private static boolean wordNamespace(String uri) {
        return WORD.equals(uri) || WORD_STRICT.equals(uri);
    }

    private static String styleKey(String value) {
        return STYLE_SEPARATOR.matcher(value.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    private static boolean captionStyle(String value) {
        if (value == null) {
            return false;
        }
        switch (styleKey(value)) {
            case "caption":
            case "figurecaption":
            case "imagecaption":
            case "illustrationcaption":
            case "photocaption":
            case "illustration":
            case "подпись":
            case "подписькрисунку":
            case "подписькиллюстрации":
            case "подписькизображению":
            case "иллюстрация":
                return true;
            default:
                return false;
        }
    }

    private static boolean figureLabel(String value) {
        if (value == null) {
            return false;
        }
        switch (styleKey(value)) {
            case "figure":
            case "illustration":
            case "image":
            case "picture":
            case "photo":
            case "рисунок":
            case "иллюстрация":
            case "изображение":
            case "фото":
                return true;
            default:
                return false;
        }
    }

    private static final class Styles extends DefaultHandler {
        private final boolean word;
        private final Map<String, String> parent = new HashMap<>();
        private final Set<String> captions = new HashSet<>();
        private final Map<String, Boolean> resolved = new HashMap<>();
        private String activeId;

        Styles(boolean word) { this.word = word; }

        @Override public void startElement(String uri, String name, String qualified, Attributes attributes) {
            if (word && wordNamespace(uri)) {
                if ("style".equals(name)) {
                    activeId = "paragraph".equals(attributes.getValue(uri, "type"))
                            ? attributes.getValue(uri, "styleId") : null;
                } else if (activeId != null && "name".equals(name)
                        && captionStyle(attributes.getValue(uri, "val"))) {
                    captions.add(activeId);
                } else if (activeId != null && "basedOn".equals(name)) {
                    parent.put(activeId, attributes.getValue(uri, "val"));
                }
            } else if (!word && ODF_STYLE.equals(uri) && "style".equals(name)
                    && "paragraph".equals(attributes.getValue(uri, "family"))) {
                String id = attributes.getValue(uri, "name");
                if (id != null) {
                    parent.put(id, attributes.getValue(uri, "parent-style-name"));
                    if (captionStyle(attributes.getValue(uri, "display-name"))) {
                        captions.add(id);
                    }
                }
            }
        }

        @Override public void endElement(String uri, String name, String qualified) {
            if (word && wordNamespace(uri) && "style".equals(name)) {
                activeId = null;
            }
        }

        boolean isCaption(String id) {
            Set<String> visited = new HashSet<>();
            boolean result = false;
            while (id != null && visited.add(id)) {
                Boolean cached = resolved.get(id);
                if (cached != null) {
                    result = cached;
                    break;
                }
                if (captions.contains(id) || captionStyle(id)) {
                    result = true;
                    break;
                }
                id = parent.get(id);
            }
            for (String style : visited) {
                resolved.put(style, result);
            }
            return result;
        }
    }

    private static final class Body extends DefaultHandler {
        final Text output = new Text();
        private final boolean word;
        private final Styles styles;
        private final ArrayDeque<Paragraph> paragraphs = new ArrayDeque<>();
        private int depth;
        private int bodyDepth;
        private int skippedDepth;
        private int wordTextDepth;
        private int wordInstructionDepth;

        Body(boolean word, Styles styles) {
            this.word = word;
            this.styles = styles;
        }

        @Override public void startElement(String uri, String name, String qualified, Attributes attributes)
                throws SAXException {
            if (++depth > MAX_XML_DEPTH) {
                throw new SAXException("Document nesting limit exceeded");
            }
            if (bodyDepth == 0) {
                if (word ? wordNamespace(uri) && "body".equals(name) && depth == 2
                        : ODF_OFFICE.equals(uri) && "text".equals(name) && depth == 3) {
                    bodyDepth = depth;
                } else if (!word) {
                    styles.startElement(uri, name, qualified, attributes);
                }
                return;
            }
            if (skippedDepth != 0) {
                return;
            }
            if (isSkipped(uri, name)) {
                skippedDepth = depth;
                return;
            }
            if (word && wordNamespace(uri)) {
                startWord(uri, name, attributes);
            } else if (!word && ODF_TEXT.equals(uri)) {
                startOdf(uri, name, attributes);
            }
        }

        private void startWord(String uri, String name, Attributes attributes) {
            if ("p".equals(name)) {
                paragraphs.push(new Paragraph(depth, false));
                return;
            }
            Paragraph paragraph = paragraphs.peek();
            if (paragraph == null) {
                return;
            }
            switch (name) {
                case "t": wordTextDepth = depth; break;
                case "instrText": wordInstructionDepth = depth; break;
                case "pStyle": paragraph.caption = styles.isCaption(attributes.getValue(uri, "val")); break;
                case "fldSimple": paragraph.caption |= figureField(attributes.getValue(uri, "instr")); break;
                case "tab": paragraph.text.space(); break;
                case "br":
                case "cr": paragraph.text.line(); break;
                case "noBreakHyphen": paragraph.text.append('\u2011'); break;
                default: break;
            }
        }

        private void startOdf(String uri, String name, Attributes attributes) {
            if ("p".equals(name) || "h".equals(name)) {
                paragraphs.push(new Paragraph(depth, styles.isCaption(attributes.getValue(uri, "style-name"))));
                return;
            }
            Paragraph paragraph = paragraphs.peek();
            if (paragraph == null) {
                return;
            }
            switch (name) {
                case "s":
                case "tab": paragraph.text.space(); break;
                case "line-break": paragraph.text.line(); break;
                case "sequence": paragraph.caption |= figureLabel(attributes.getValue(uri, "name")); break;
                default: break;
            }
        }

        private boolean isSkipped(String uri, String name) {
            if (word) {
                return wordNamespace(uri) && ("drawing".equals(name) || "pict".equals(name)
                        || "object".equals(name) || "del".equals(name) || "moveFrom".equals(name));
            }
            return (ODF_DRAW.equals(uri) && ("image".equals(name) || "object".equals(name)
                    || "object-ole".equals(name) || "plugin".equals(name)))
                    || (ODF_OFFICE.equals(uri) && ("annotation".equals(name) || "binary-data".equals(name)))
                    || (ODF_TEXT.equals(uri) && ("tracked-changes".equals(name) || "hidden-text".equals(name)));
        }

        @Override public void characters(char[] chars, int start, int length) {
            Paragraph paragraph = paragraphs.peek();
            if (paragraph == null || skippedDepth != 0) {
                return;
            }
            if (!word || wordTextDepth != 0) {
                paragraph.text.append(chars, start, length);
            } else if (wordInstructionDepth != 0) {
                paragraph.instructions.append(chars, start, length);
            }
        }

        @Override public void endElement(String uri, String name, String qualified) {
            if (skippedDepth != 0) {
                if (skippedDepth == depth) {
                    skippedDepth = 0;
                }
            } else if (bodyDepth == depth) {
                bodyDepth = 0;
            } else if (wordTextDepth == depth) {
                wordTextDepth = 0;
            } else if (wordInstructionDepth == depth) {
                wordInstructionDepth = 0;
            } else if (!paragraphs.isEmpty() && paragraphs.peek().depth == depth) {
                Paragraph paragraph = paragraphs.pop();
                if (!paragraph.caption && !figureField(paragraph.instructions)) {
                    Text target = paragraphs.isEmpty() ? output : paragraphs.peek().text;
                    target.paragraph(paragraph.text);
                }
            }
            depth--;
        }
    }

    private static boolean figureField(CharSequence instructions) {
        if (instructions == null || instructions.length() == 0) {
            return false;
        }
        Matcher field = SEQUENCE.matcher(instructions);
        while (field.find()) {
            if (figureLabel(field.group(1) != null ? field.group(1) : field.group(2))) {
                return true;
            }
        }
        return false;
    }

    private static final class Paragraph {
        private final int depth;
        private final Text text = new Text();
        private final StringBuilder instructions = new StringBuilder();
        private boolean caption;

        Paragraph(int depth, boolean caption) {
            this.depth = depth;
            this.caption = caption;
        }
    }

    private static final class Text {
        final StringBuilder value = new StringBuilder();
        private char separator;

        void append(char[] chars, int start, int length) {
            for (int i = start, end = start + length; i < end; i++) {
                append(chars[i]);
            }
        }

        void append(char ch) {
            if (Character.isWhitespace(ch) || Character.isSpaceChar(ch)) {
                space();
            } else if (ch != '\u00ad') {
                flushSeparator();
                value.append(ch);
            }
        }

        void paragraph(Text paragraph) {
            if (paragraph.value.length() != 0) {
                line();
                flushSeparator();
                value.append(paragraph.value);
                line();
            }
        }

        void space() {
            if (separator != '\n') {
                separator = ' ';
            }
        }

        void line() { separator = '\n'; }

        private void flushSeparator() {
            if (separator != 0 && value.length() != 0) {
                value.append(separator);
            }
            separator = 0;
        }
    }

    private static final class Title extends DefaultHandler {
        final StringBuilder value = new StringBuilder();
        private boolean reading;

        @Override public void startElement(String uri, String name, String qualified, Attributes attributes) {
            if (DC.equals(uri) && "title".equals(name) && value.length() == 0) {
                reading = true;
            }
        }

        @Override public void characters(char[] chars, int start, int length) {
            if (reading && value.length() < 512) {
                value.append(chars, start, Math.min(length, 512 - value.length()));
            }
        }

        @Override public void endElement(String uri, String name, String qualified) {
            if (DC.equals(uri) && "title".equals(name)) {
                reading = false;
            }
        }
    }
}
