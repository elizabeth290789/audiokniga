package com.audiokniga.app;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

/** Extracts readable text without loading media or resolving external XML resources. */
public final class BookParser {
    private static final int TEXT_LIMIT = 16 * 1024 * 1024;
    private static final int EPUB_DOCUMENTS_LIMIT = 24 * 1024 * 1024;
    private static final long EPUB_EXPANDED_LIMIT = 256L * 1024 * 1024;
    private static final int EPUB_ENTRIES_LIMIT = 10_000;
    private static final Charset WINDOWS_1251 = Charset.forName("windows-1251");
    private static final Pattern XML_ENCODING = Pattern.compile(
            "^<\\?xml\\s+[^?>]*?encoding\\s*=\\s*['\"]([A-Za-z0-9._-]+)['\"]");
    private static final Pattern XHTML_DOCTYPE = Pattern.compile(
            "<!DOCTYPE\\s+html(?:\\s+[^<>\\[]*)?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAMED_ENTITY = Pattern.compile("&([a-zA-Z][a-zA-Z0-9]+);");
    private static final Map<String, String> XHTML_ENTITIES = new HashMap<>();
    private static final Set<String> HIDDEN_ELEMENTS = new HashSet<>(Arrays.asList(
            "audio", "binary", "canvas", "figcaption", "head", "iframe", "image", "img",
            "math", "nav", "noscript", "object", "picture", "script", "style", "svg", "video"));
    private static final Set<String> CAPTION_CLASSES = new HashSet<>(Arrays.asList(
            "caption", "figcaption", "figure-caption", "illustration-caption", "image-caption",
            "img-caption", "photo-caption"));
    private static final Set<String> BLOCK_ELEMENTS = new HashSet<>(Arrays.asList(
            "address", "article", "blockquote", "br", "caption", "cite", "dd", "div", "dl", "dt",
            "empty-line", "epigraph", "h1", "h2", "h3", "h4", "h5", "h6", "hr", "li", "ol", "p",
            "poem", "pre", "section", "stanza", "subtitle", "table", "td", "text-author", "th",
            "title", "tr", "ul", "v"));

    static {
        // Legacy XHTML documents can refer to these names without embedding a DTD.
        String[] names = ("nbsp iexcl cent pound curren yen brvbar sect uml copy ordf laquo not shy "
                + "reg macr deg plusmn sup2 sup3 acute micro para middot cedil sup1 ordm raquo "
                + "frac14 frac12 frac34 iquest Agrave Aacute Acirc Atilde Auml Aring AElig Ccedil "
                + "Egrave Eacute Ecirc Euml Igrave Iacute Icirc Iuml ETH Ntilde Ograve Oacute Ocirc "
                + "Otilde Ouml times Oslash Ugrave Uacute Ucirc Uuml Yacute THORN szlig agrave "
                + "aacute acirc atilde auml aring aelig ccedil egrave eacute ecirc euml igrave iacute "
                + "icirc iuml eth ntilde ograve oacute ocirc otilde ouml divide oslash ugrave uacute "
                + "ucirc uuml yacute thorn yuml").split(" ");
        for (int i = 0; i < names.length; i++) {
            XHTML_ENTITIES.put(names[i], "&#" + (160 + i) + ";");
        }
        String[] punctuation = ("OElig:338 oelig:339 Scaron:352 scaron:353 Yuml:376 fnof:402 "
                + "circ:710 tilde:732 ensp:8194 emsp:8195 thinsp:8201 zwnj:8204 zwj:8205 "
                + "ndash:8211 mdash:8212 lsquo:8216 rsquo:8217 sbquo:8218 ldquo:8220 rdquo:8221 "
                + "bdquo:8222 dagger:8224 Dagger:8225 bull:8226 hellip:8230 permil:8240 "
                + "prime:8242 Prime:8243 lsaquo:8249 rsaquo:8250 euro:8364 "
                + "trade:8482 minus:8722").split(" ");
        for (String entity : punctuation) {
            String[] parts = entity.split(":");
            XHTML_ENTITIES.put(parts[0], "&#" + parts[1] + ";");
        }
    }

    private BookParser() {}

    public static final class ParsedBook {
        public final String title;
        public final String text;

        private ParsedBook(String title, String text) {
            this.title = title;
            this.text = text;
        }
    }

    /** Consumes the stream, which remains open for the caller to close. */
    public static ParsedBook parse(InputStream input, String filename) throws IOException {
        String extension = extension(filename);
        String title = "";
        String text;
        switch (extension) {
            case "txt":
                text = decode(readLimited(input), false).replace("\r\n", "\n").replace('\r', '\n');
                break;
            case "fb2":
                ContentHandler fb2 = new ContentHandler(true);
                parseXml(decode(readLimited(input), true), fb2, false);
                title = fb2.title.toString();
                text = fb2.text.toString();
                break;
            case "epub":
                ParsedBook epub = parseEpub(input);
                title = epub.title;
                text = epub.text;
                break;
            default:
                throw new IOException("Поддерживаются файлы EPUB, FB2 и TXT.");
        }
        text = text.trim();
        if (text.isEmpty()) {
            throw new IOException("В файле нет текста для озвучивания.");
        }
        if (title.isEmpty()) {
            title = filename.substring(0, filename.lastIndexOf('.')).trim();
            if (title.isEmpty()) {
                title = filename;
            }
        }
        return new ParsedBook(title, text);
    }

    private static ParsedBook parseEpub(InputStream input) throws IOException {
        Map<String, byte[]> resources = readEpubResources(input);
        ContainerHandler container = new ContainerHandler();
        parseXml(decode(requireResource(resources, "META-INF/container.xml"), true), container, false);
        if (container.packagePath == null) {
            throw new IOException("В EPUB отсутствует описание книги.");
        }
        String packagePath = resolvePath("", container.packagePath);
        PackageHandler book = new PackageHandler();
        parseXml(decode(requireResource(resources, packagePath), true), book, false);
        if (book.spine.isEmpty()) {
            throw new IOException("В EPUB отсутствует порядок глав.");
        }
        StringBuilder text = new StringBuilder();
        for (String id : book.spine) {
            PackageResource resource = book.manifest.get(id);
            if (resource == null) {
                throw new IOException("В EPUB отсутствует глава из оглавления.");
            }
            if ("image/svg+xml".equals(resource.type)) {
                continue;
            }
            String path = resolvePath(packagePath, resource.href);
            ContentHandler chapter = new ContentHandler(false);
            parseXml(decode(requireResource(resources, path), true), chapter, true);
            String chapterText = chapter.text.toString();
            if (!chapterText.isEmpty()) {
                if ((long) text.length() + chapterText.length() + 2 > TEXT_LIMIT) {
                    throw tooLarge();
                }
                if (text.length() > 0) {
                    text.append("\n\n");
                }
                text.append(chapterText);
            }
        }
        return new ParsedBook(book.title.toString(), text.toString());
    }

    private static Map<String, byte[]> readEpubResources(InputStream input) throws IOException {
        Map<String, byte[]> resources = new HashMap<>();
        byte[] buffer = new byte[8192];
        long expandedBytes = 0;
        int retainedBytes = 0;
        int entryCount = 0;
        // Closing the inflater must not close the caller's stream.
        try (ZipInputStream zip = new ZipInputStream(new FilterInputStream(input) {
            @Override public void close() {}
        })) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entryCount > EPUB_ENTRIES_LIMIT) {
                    throw tooLarge();
                }
                String name = entry.getName();
                String suffix = extension(name);
                ByteArrayOutputStream document = null;
                if (!entry.isDirectory() && (suffix.equals("xml") || suffix.equals("opf")
                        || suffix.equals("xhtml") || suffix.equals("html") || suffix.equals("htm"))) {
                    document = new ByteArrayOutputStream();
                }
                int length;
                while ((length = zip.read(buffer)) != -1) {
                    expandedBytes += length;
                    if (expandedBytes > EPUB_EXPANDED_LIMIT) {
                        throw tooLarge();
                    }
                    if (document != null) {
                        retainedBytes += length;
                        if (retainedBytes > EPUB_DOCUMENTS_LIMIT || document.size() + length > TEXT_LIMIT) {
                            throw tooLarge();
                        }
                        document.write(buffer, 0, length);
                    }
                }
                if (document != null && resources.put(name, document.toByteArray()) != null) {
                    throw new IOException("В EPUB повторяются файлы книги.");
                }
            }
        }
        return resources;
    }

    private static String resolvePath(String parentFile, String href) throws IOException {
        try {
            URI reference = new URI(href.replace(" ", "%20"));
            String path = reference.getPath();
            if (reference.isAbsolute() || reference.getRawAuthority() != null || path == null
                    || path.isEmpty() || path.startsWith("/") || path.contains("\\")) {
                throw new URISyntaxException(href, "Not a relative EPUB path");
            }
            String directory = parentFile.substring(0, parentFile.lastIndexOf('/') + 1);
            ArrayDeque<String> parts = new ArrayDeque<>();
            for (String part : (directory + path).split("/")) {
                if (part.isEmpty() || ".".equals(part)) {
                    continue;
                }
                if ("..".equals(part)) {
                    if (parts.isEmpty()) {
                        throw new URISyntaxException(href, "Path escapes EPUB archive");
                    }
                    parts.removeLast();
                } else {
                    parts.addLast(part);
                }
            }
            StringBuilder result = new StringBuilder();
            for (String part : parts) {
                if (result.length() > 0) {
                    result.append('/');
                }
                result.append(part);
            }
            return result.toString();
        } catch (URISyntaxException e) {
            throw new IOException("В EPUB обнаружен недопустимый путь к главе.", e);
        }
    }

    private static byte[] requireResource(Map<String, byte[]> resources, String path) throws IOException {
        byte[] result = resources.get(path);
        if (result == null) {
            throw new IOException("В EPUB отсутствует файл: " + path);
        }
        return result;
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int length;
        while ((length = input.read(buffer)) != -1) {
            if (bytes.size() + length > TEXT_LIMIT) {
                throw tooLarge();
            }
            bytes.write(buffer, 0, length);
        }
        return bytes.toByteArray();
    }

    private static IOException tooLarge() {
        return new IOException("Файл слишком большой для импорта.");
    }

    private static String decode(byte[] bytes, boolean xml) throws IOException {
        Charset charset = StandardCharsets.UTF_8;
        int start = 0;
        if (hasPrefix(bytes, 0xef, 0xbb, 0xbf)) {
            start = 3;
        } else if (hasPrefix(bytes, 0xfe, 0xff)) {
            charset = StandardCharsets.UTF_16BE;
            start = 2;
        } else if (hasPrefix(bytes, 0xff, 0xfe)) {
            charset = StandardCharsets.UTF_16LE;
            start = 2;
        } else if (xml && hasPrefix(bytes, 0, '<', 0)) {
            charset = StandardCharsets.UTF_16BE;
        } else if (xml && hasPrefix(bytes, '<', 0)) {
            charset = StandardCharsets.UTF_16LE;
        } else if (xml) {
            String header = new String(bytes, 0, Math.min(bytes.length, 512), StandardCharsets.ISO_8859_1);
            Matcher encoding = XML_ENCODING.matcher(header);
            if (encoding.find()) {
                try {
                    charset = Charset.forName(encoding.group(1));
                } catch (IllegalArgumentException e) {
                    throw new IOException("Кодировка текста не поддерживается.", e);
                }
            }
        }
        try {
            return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, start, bytes.length - start)).toString();
        } catch (CharacterCodingException e) {
            if (!xml && start == 0) {
                return new String(bytes, WINDOWS_1251);
            }
            throw new IOException("Не удалось прочитать текст в указанной кодировке.", e);
        }
    }

    private static boolean hasPrefix(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xff) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static void parseXml(String source, DefaultHandler handler, boolean xhtml) throws IOException {
        source = stripDocumentType(source, xhtml);
        if (xhtml) {
            source = replaceXhtmlEntities(source);
        }
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            XMLReader reader = factory.newSAXParser().getXMLReader();
            reader.setFeature("http://xml.org/sax/features/external-general-entities", false);
            reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            reader.setEntityResolver((publicId, systemId) -> {
                throw new SAXException("External entities are disabled");
            });
            reader.setContentHandler(handler);
            reader.setErrorHandler(new DefaultHandler() {
                @Override public void error(SAXParseException e) throws SAXException {
                    throw e;
                }
            });
            reader.parse(new InputSource(new StringReader(source)));
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Не удалось разобрать структуру книги.", e);
        }
    }

    private static String stripDocumentType(String source, boolean xhtml) throws IOException {
        int offset = 0;
        while (offset < source.length()) {
            if (Character.isWhitespace(source.charAt(offset))) {
                offset++;
            } else if (source.startsWith("<?", offset) || source.startsWith("<!--", offset)) {
                String closing = source.startsWith("<?", offset) ? "?>" : "-->";
                int end = source.indexOf(closing, offset + 2);
                if (end == -1) {
                    return source;
                }
                offset = end + closing.length();
            } else if (source.regionMatches(xhtml, offset, "<!DOCTYPE", 0, 9)) {
                Matcher doctype = XHTML_DOCTYPE.matcher(source).region(offset, source.length());
                if (!xhtml || !doctype.lookingAt()) {
                    throw new IOException("DTD внутри книги не поддерживается.");
                }
                String withoutDoctype = source.substring(0, offset) + source.substring(doctype.end());
                return stripDocumentType(withoutDoctype, false);
            } else {
                return source;
            }
        }
        return source;
    }

    private static String replaceXhtmlEntities(String source) {
        Matcher matcher = NAMED_ENTITY.matcher(source);
        StringBuffer output = null;
        int protectedUntil = 0;
        int scanFrom = 0;
        while (matcher.find()) {
            // Entity references in CDATA and comments are literal text.
            while (scanFrom <= matcher.start()) {
                int markup = source.indexOf("<!", scanFrom);
                if (markup == -1) {
                    scanFrom = source.length();
                    break;
                }
                if (markup > matcher.start()) {
                    scanFrom = markup;
                    break;
                }
                if (source.startsWith("<![CDATA[", markup) || source.startsWith("<!--", markup)) {
                    String marker = source.startsWith("<!--", markup) ? "-->" : "]]>";
                    int end = source.indexOf(marker, markup + 3);
                    protectedUntil = end == -1 ? source.length() : end + marker.length();
                    scanFrom = protectedUntil;
                } else {
                    scanFrom = markup + 2;
                }
            }
            String replacement = XHTML_ENTITIES.get(matcher.group(1));
            if (matcher.start() >= protectedUntil && replacement != null) {
                if (output == null) {
                    output = new StringBuffer();
                }
                matcher.appendReplacement(output, replacement);
            }
        }
        if (output == null) {
            return source;
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static final class ContainerHandler extends DefaultHandler {
        String packagePath;

        @Override public void startElement(String uri, String name, String qualified, Attributes attributes) {
            if ("rootfile".equals(name) && packagePath == null) {
                packagePath = attributes.getValue("full-path");
            }
        }
    }

    private static final class PackageResource {
        final String href;
        final String type;

        PackageResource(String href, String type) {
            this.href = href;
            this.type = type;
        }
    }

    private static final class PackageHandler extends DefaultHandler {
        final Map<String, PackageResource> manifest = new HashMap<>();
        final List<String> spine = new ArrayList<>();
        final TextAccumulator title = new TextAccumulator();
        private boolean inTitle;
        private boolean inMetadata;
        private boolean inManifest;
        private boolean inSpine;

        @Override public void startElement(String uri, String name, String qualified, Attributes attributes) {
            switch (name) {
                case "metadata": inMetadata = true; break;
                case "manifest": inManifest = true; break;
                case "spine": inSpine = true; break;
                case "title": inTitle = inMetadata && title.isEmpty(); break;
                case "item":
                    String id = attributes.getValue("id");
                    String href = attributes.getValue("href");
                    if (inManifest && id != null && href != null) {
                        manifest.put(id, new PackageResource(href, attributes.getValue("media-type")));
                    }
                    break;
                case "itemref":
                    String idref = attributes.getValue("idref");
                    if (inSpine && idref != null && !"no".equals(attributes.getValue("linear"))) {
                        spine.add(idref);
                    }
                    break;
                default: break;
            }
        }

        @Override public void characters(char[] chars, int start, int length) {
            if (inTitle) {
                title.append(chars, start, length);
            }
        }

        @Override public void endElement(String uri, String name, String qualified) {
            switch (name) {
                case "metadata": inMetadata = false; break;
                case "manifest": inManifest = false; break;
                case "spine": inSpine = false; break;
                case "title": inTitle = false; break;
                default: break;
            }
        }
    }

    private static final class ContentHandler extends DefaultHandler {
        final TextAccumulator title = new TextAccumulator();
        final TextAccumulator text = new TextAccumulator();
        private final boolean fb2;
        private int depth;
        private int skippedDepth;
        private int bodyDepth;
        private int titleDepth;

        ContentHandler(boolean fb2) {
            this.fb2 = fb2;
        }

        @Override public void startElement(String uri, String name, String qualified, Attributes attributes)
                throws SAXException {
            depth++;
            if (depth == 1 && fb2 && !"FictionBook".equals(name)) {
                throw new SAXException("Missing FictionBook root");
            }
            if (skippedDepth != 0) {
                return;
            }
            if (HIDDEN_ELEMENTS.contains(name) || isCaption(attributes)
                    || (fb2 && "caption".equals(name)) || "true".equals(attributes.getValue("aria-hidden"))
                    || attributes.getValue("hidden") != null) {
                skippedDepth = depth;
                return;
            }
            if (bodyDepth == 0) {
                if (fb2 && "book-title".equals(name) && title.isEmpty()) {
                    titleDepth = depth;
                } else if ("body".equals(name)) {
                    text.breakLine();
                    bodyDepth = depth;
                }
            } else if (BLOCK_ELEMENTS.contains(name)) {
                text.breakLine();
            }
        }

        @Override public void characters(char[] chars, int start, int length) {
            if (skippedDepth == 0) {
                if (bodyDepth != 0) {
                    text.append(chars, start, length);
                } else if (titleDepth != 0) {
                    title.append(chars, start, length);
                }
            }
        }

        @Override public void endElement(String uri, String name, String qualified) {
            if (skippedDepth != 0) {
                if (skippedDepth == depth) {
                    skippedDepth = 0;
                }
            } else if (depth == bodyDepth) {
                bodyDepth = 0;
            } else if (depth == titleDepth) {
                titleDepth = 0;
            } else if (bodyDepth != 0 && BLOCK_ELEMENTS.contains(name)) {
                text.breakLine();
            }
            depth--;
        }

        private static boolean isCaption(Attributes attributes) {
            String classes = attributes.getValue("class");
            if (classes != null) {
                for (String token : classes.toLowerCase(Locale.ROOT).split("\\s+")) {
                    if (CAPTION_CLASSES.contains(token)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private static final class TextAccumulator {
        private final StringBuilder value = new StringBuilder();
        private boolean needSpace;
        private boolean needLine;

        void append(char[] chars, int start, int length) {
            for (int i = start; i < start + length; i++) {
                char ch = chars[i];
                if (Character.isWhitespace(ch) || Character.isSpaceChar(ch)) {
                    needSpace = true;
                } else if (ch != '\u00ad') {
                    if (value.length() > 0) {
                        if (needLine) {
                            value.append('\n');
                        } else if (needSpace) {
                            value.append(' ');
                        }
                    }
                    value.append(ch);
                    needLine = false;
                    needSpace = false;
                }
            }
        }

        void breakLine() {
            needLine = true;
            needSpace = false;
        }

        boolean isEmpty() {
            return value.length() == 0;
        }

        @Override public String toString() {
            return value.toString();
        }
    }
}
