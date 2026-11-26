package com.audiokniga.app;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Extracts the text of an RTF document without rendering embedded content. */
final class RtfBookParser {
    private static final long FILE_LIMIT = 64L * 1024 * 1024;
    private static final int TEXT_LIMIT = 16 * 1024 * 1024;
    private static final int MAX_DEPTH = 128;
    private static final int MAX_CATALOG_SIZE = 8_192;
    private static final int METADATA_LIMIT = 4_096;
    private static final Charset WESTERN = Charset.forName("windows-1252");
    private static final Set<String> IGNORED_DESTINATIONS = new HashSet<>(Arrays.asList(
            "annotation", "atnauthor", "atnid", "background", "colortbl", "comment",
            "datafield", "datastore", "do", "doccomm", "filetbl", "fontemb", "fontfile",
            "footer", "footerf", "footerl", "footerr", "footnote", "formfield",
            "header", "headerf", "headerl", "headerr", "listoverridetable", "listtable",
            "nonshppict", "object", "objdata", "pict", "pn", "pntext", "listtext",
            "revtbl", "rsidtbl", "shp", "shpinst", "shppict", "shprslt", "themedata",
            "xe", "tc", "xmlattrname", "xmlattrvalue", "xmlnstbl"));
    private static final Set<String> CAPTION_STYLES = new HashSet<>(Arrays.asList(
            "caption", "figurecaption", "illustrationcaption", "imagecaption",
            "picturecaption", "photocaption", "подпись", "подписькрисунку",
            "подписькиллюстрации", "подписькизображению", "подписьподрисунком"));
    private static final Pattern SEQUENCE_CAPTION = Pattern.compile(
            "^\\s*SEQ\\s+\"?(?:figure|illustration|image|photo|picture|рисунок|иллюстрация)"
                    + "(?=\"|\\s|$)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private enum Destination {
        BODY, SKIP, INFO, TITLE, FONT_TABLE, STYLES, STYLE_NAME, FIELD_INSTRUCTION
    }

    private static final class Group {
        Destination destination = Destination.BODY;
        int font = -1;
        int codePage;
        int paragraphStyle;
        int unicodeFallback = 1;
        boolean hidden;
        boolean atStart = true;
        boolean optional;
        boolean unicodeChoices;
        boolean unicodeChoice;
        int choiceChildren;
        StringBuilder metadata;
        boolean ownsMetadata;
        int styleId = -1;

        Group() {}

        Group(Group parent) {
            destination = parent.destination;
            font = parent.font;
            codePage = parent.codePage;
            paragraphStyle = parent.paragraphStyle;
            unicodeFallback = parent.unicodeFallback;
            hidden = parent.hidden;
            metadata = parent.metadata;
        }
    }

    private final BufferedInputStream input;
    private final StringBuilder text = new StringBuilder();
    private final ByteArrayOutputStream encoded = new ByteArrayOutputStream();
    private final ArrayDeque<Group> stack = new ArrayDeque<>();
    private final Map<Integer, Integer> fontCodePages = new HashMap<>();
    private final Set<Integer> captionStyles = new HashSet<>();
    private Group group = new Group();
    private String title = "";
    private long bytesRead;
    private int nextByte = -2;
    private int defaultCodePage = 1252;
    private int defaultFont = -1;
    private int fallbackRemaining;
    private int paragraphStart;
    private boolean captionParagraph;

    private RtfBookParser(InputStream input) {
        this.input = new BufferedInputStream(input);
    }

    static BookParser.ParsedBook parse(InputStream input) throws IOException {
        return new RtfBookParser(input).readDocument();
    }

    private BookParser.ParsedBook readDocument() throws IOException {
        if (peek() == 0xef) {
            read();
            if (read() != 0xbb || read() != 0xbf) {
                throw invalid();
            }
        }
        while (white(peek())) {
            read();
        }
        if (read() != '{' || read() != '\\' || read() != 'r' || read() != 't' || read() != 'f'
                || !digit(peek())) {
            throw invalid();
        }
        readNumber();
        if (peek() == ' ') {
            read();
        }
        group.atStart = false;

        boolean closed = false;
        for (int value; (value = read()) != -1;) {
            if (closed) {
                if (!white(value) && value != 0) {
                    throw invalid();
                }
                continue;
            }
            switch (value) {
                case '{':
                    openGroup();
                    break;
                case '}':
                    flushEncoded();
                    finishMetadata();
                    fallbackRemaining = 0;
                    if (stack.isEmpty()) {
                        finishParagraph();
                        closed = true;
                    } else {
                        group = stack.pop();
                    }
                    break;
                case '\\':
                    control();
                    break;
                case '\r':
                case '\n':
                    break;
                default:
                    if (value != ' ' && value != '\t') {
                        group.atStart = false;
                    }
                    appendByte(value);
                    break;
            }
        }
        if (!closed) {
            throw invalid();
        }
        String result = text.toString().trim();
        if (result.isEmpty()) {
            throw new IOException("В документе RTF нет текста для озвучивания.");
        }
        return new BookParser.ParsedBook(title, result);
    }

    private void openGroup() throws IOException {
        if (stack.size() >= MAX_DEPTH) {
            throw new IOException("В документе RTF слишком много вложенных элементов.");
        }
        flushEncoded();
        fallbackRemaining = 0;
        Group parent = group;
        stack.push(parent);
        group = new Group(parent);
        if (parent.unicodeChoices) {
            group.destination = Destination.SKIP;
            group.unicodeChoice = ++parent.choiceChildren == 2;
        }
    }

    private void control() throws IOException {
        int value = read();
        if (!letter(value)) {
            controlSymbol(value);
            return;
        }
        StringBuilder name = new StringBuilder(16);
        do {
            if (name.length() == 40) {
                throw invalid();
            }
            name.append((char) value);
            value = peek();
            if (letter(value)) {
                read();
            }
        } while (letter(value));

        boolean hasNumber = digit(value) || value == '-' && minusHasDigits();
        int argument = hasNumber ? readNumber() : 0;
        if (peek() == ' ') {
            read();
        }

        String word = name.toString();
        if ("bin".equals(word)) {
            if (!hasNumber || argument < 0) {
                throw invalid();
            }
            if (argument > FILE_LIMIT - bytesRead + (nextByte >= 0 ? 1 : 0)) {
                throw tooLarge();
            }
            skipFallback();
            for (int i = 0; i < argument; i++) {
                if (read() < 0) {
                    throw invalid();
                }
            }
            return;
        }
        if (skipFallback()) {
            return;
        }
        if ("u".equals(word)) {
            if (!hasNumber || argument < Short.MIN_VALUE || argument > Character.MAX_VALUE) {
                throw invalid();
            }
            group.atStart = false;
            appendText(Character.toString((char) argument));
            fallbackRemaining = group.unicodeFallback;
            return;
        }
        boolean first = group.atStart;
        group.atStart = false;
        if (first) {
            flushEncoded();
            if (destination(word)) {
                return;
            }
        }
        if (group.destination == Destination.SKIP) {
            return;
        }
        applyControl(word, argument, hasNumber);
    }

    private void controlSymbol(int value) throws IOException {
        if (value == -1) {
            throw invalid();
        }
        if (value == '*') {
            if (!skipFallback() && group.atStart) {
                group.optional = true;
            }
            return;
        }
        if (value == '\'') {
            int high = hex(read());
            int low = hex(read());
            if (high < 0 || low < 0) {
                throw invalid();
            }
            group.atStart = false;
            appendByte(high * 16 + low);
            return;
        }
        if (skipFallback()) {
            return;
        }
        group.atStart = false;
        switch (value) {
            case '\\':
            case '{':
            case '}':
                appendText(Character.toString((char) value));
                break;
            case '~':
                appendText(" ");
                break;
            case '_':
                appendText("‑");
                break;
            case '\r':
            case '\n':
                if (value == '\r' && peek() == '\n') {
                    read();
                }
                paragraphBreak();
                break;
            default:
                break;
        }
    }

    private boolean destination(String word) throws IOException {
        if ("ud".equals(word) && group.unicodeChoice) {
            group.destination = stack.peek().destination;
            return true;
        }
        if (group.destination == Destination.SKIP) {
            return true;
        }
        if ("upr".equals(word)) {
            group.unicodeChoices = true;
            return true;
        }
        if (IGNORED_DESTINATIONS.contains(word)) {
            group.destination = Destination.SKIP;
            return true;
        }
        if ("fldinst".equals(word) && group.destination == Destination.BODY) {
            startMetadata(Destination.FIELD_INSTRUCTION);
            return true;
        }
        if (group.optional) {
            group.destination = Destination.SKIP;
            return true;
        }
        if ("info".equals(word) && group.destination == Destination.BODY) {
            group.destination = Destination.INFO;
            return true;
        }
        if ("title".equals(word) && group.destination == Destination.INFO) {
            startMetadata(Destination.TITLE);
            return true;
        }
        if ("fonttbl".equals(word) && group.destination == Destination.BODY) {
            group.destination = Destination.FONT_TABLE;
            return true;
        }
        if ("stylesheet".equals(word) && group.destination == Destination.BODY) {
            group.destination = Destination.STYLES;
            return true;
        }
        return false;
    }

    private void applyControl(String word, int argument, boolean hasNumber) throws IOException {
        if (group.destination == Destination.FONT_TABLE) {
            if ("f".equals(word) && hasNumber) {
                group.font = argument;
            } else if ("fcharset".equals(word) && hasNumber) {
                rememberFont(familyCodePage(argument), false);
            } else if ("cpg".equals(word) && hasNumber) {
                rememberFont(argument, true);
            }
            return;
        }
        if (group.destination == Destination.STYLES && "s".equals(word) && hasNumber) {
            startMetadata(Destination.STYLE_NAME);
            group.styleId = argument;
            return;
        }
        switch (word) {
            case "ansi":
            case "ansicpg":
            case "mac":
            case "pc":
            case "pca":
                if (stack.isEmpty() && group.destination == Destination.BODY) {
                    flushEncoded();
                    if ("ansi".equals(word)) {
                        defaultCodePage = 1252;
                    } else if ("mac".equals(word)) {
                        defaultCodePage = 10000;
                    } else if ("pc".equals(word)) {
                        defaultCodePage = 437;
                    } else if ("pca".equals(word)) {
                        defaultCodePage = 850;
                    } else if (hasNumber && argument > 0) {
                        defaultCodePage = argument;
                    }
                }
                break;
            case "deff":
                if (hasNumber && stack.isEmpty()) {
                    flushEncoded();
                    defaultFont = argument;
                }
                break;
            case "cpg":
                if (hasNumber && argument > 0) {
                    flushEncoded();
                    group.codePage = argument;
                }
                break;
            case "f":
            case "plain":
                if ("plain".equals(word) || hasNumber) {
                    flushEncoded();
                    group.font = "plain".equals(word) ? defaultFont : argument;
                }
                break;
            case "uc":
                if (hasNumber && argument >= 0 && argument <= 32_767) {
                    group.unicodeFallback = argument;
                }
                break;
            case "v":
            case "deleted":
                flushEncoded();
                group.hidden = !hasNumber || argument != 0;
                break;
            case "pard":
                flushEncoded();
                group.paragraphStyle = 0;
                break;
            case "s":
                if (hasNumber) {
                    flushEncoded();
                    group.paragraphStyle = argument;
                }
                break;
            case "par":
            case "page":
            case "sect":
            case "row":
            case "nestrow":
                paragraphBreak();
                break;
            case "line":
                appendText("\n");
                break;
            case "tab":
            case "cell":
            case "nestcell":
            case "enspace":
            case "emspace":
            case "qmspace":
                appendText(" ");
                break;
            case "emdash":
                appendText("—");
                break;
            case "endash":
                appendText("–");
                break;
            case "bullet":
                appendText("•");
                break;
            case "lquote":
                appendText("‘");
                break;
            case "rquote":
                appendText("’");
                break;
            case "ldblquote":
                appendText("“");
                break;
            case "rdblquote":
                appendText("”");
                break;
            default:
                break;
        }
    }

    private void rememberFont(int codePage, boolean explicit) throws IOException {
        if (group.font < 0 || codePage <= 0) {
            return;
        }
        if (fontCodePages.size() >= MAX_CATALOG_SIZE && !fontCodePages.containsKey(group.font)) {
            throw tooLarge();
        }
        if (explicit) {
            fontCodePages.put(group.font, codePage);
        } else {
            fontCodePages.putIfAbsent(group.font, codePage);
        }
    }

    private void startMetadata(Destination destination) {
        group.destination = destination;
        group.metadata = new StringBuilder();
        group.ownsMetadata = true;
    }

    private void finishMetadata() throws IOException {
        if (!group.ownsMetadata) {
            return;
        }
        String value = group.metadata.toString().trim();
        if (group.destination == Destination.TITLE && title.isEmpty()) {
            title = value;
        } else if (group.destination == Destination.STYLE_NAME && group.styleId >= 0) {
            String canonical = value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}]", "");
            if (CAPTION_STYLES.contains(canonical)) {
                if (captionStyles.size() >= MAX_CATALOG_SIZE && !captionStyles.contains(group.styleId)) {
                    throw tooLarge();
                }
                captionStyles.add(group.styleId);
            }
        } else if (group.destination == Destination.FIELD_INSTRUCTION
                && SEQUENCE_CAPTION.matcher(value).find()) {
            captionParagraph = true;
            text.setLength(paragraphStart);
        }
    }

    private boolean skipFallback() {
        if (fallbackRemaining == 0) {
            return false;
        }
        fallbackRemaining--;
        return true;
    }

    private void appendByte(int value) throws IOException {
        if (skipFallback() || !canWrite() || (value < 32 && value != '\t')) {
            return;
        }
        if (group.metadata != null && encoded.size() >= METADATA_LIMIT * 4) {
            return;
        }
        if (encoded.size() == TEXT_LIMIT) {
            throw tooLarge();
        }
        encoded.write(value == '\t' ? ' ' : value);
    }

    private boolean canWrite() {
        return !group.hidden && !group.unicodeChoices
                && (group.destination == Destination.TITLE || group.destination == Destination.STYLE_NAME
                || group.destination == Destination.FIELD_INSTRUCTION
                || (group.destination == Destination.BODY && !captionParagraph));
    }

    private void flushEncoded() throws IOException {
        if (encoded.size() == 0) {
            return;
        }
        int codePage = group.codePage;
        if (codePage == 0) {
            int font = group.font >= 0 ? group.font : defaultFont;
            codePage = fontCodePages.getOrDefault(font, defaultCodePage);
        }
        String value = new String(encoded.toByteArray(), charset(codePage));
        encoded.reset();
        writeText(value);
    }

    private void appendText(String value) throws IOException {
        if (canWrite()) {
            flushEncoded();
            writeText(value);
        }
    }

    private void writeText(String value) throws IOException {
        if (group.destination == Destination.TITLE || group.destination == Destination.STYLE_NAME
                || group.destination == Destination.FIELD_INSTRUCTION) {
            int room = METADATA_LIMIT - group.metadata.length();
            group.metadata.append(value, 0, Math.min(room, value.length()));
            return;
        }
        if (captionStyles.contains(group.paragraphStyle)) {
            captionParagraph = true;
            text.setLength(paragraphStart);
            return;
        }
        if (captionParagraph) {
            return;
        }
        if (value.length() > TEXT_LIMIT - text.length()) {
            throw tooLarge();
        }
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current != '\u00ad' && current != '\u0000') {
                text.append(current == '\u00a0' ? ' ' : current);
            }
        }
    }

    private void paragraphBreak() throws IOException {
        if (group.destination == Destination.BODY && !group.hidden && !group.unicodeChoices) {
            flushEncoded();
            finishParagraph();
        }
    }

    private void finishParagraph() throws IOException {
        while (text.length() > paragraphStart && Character.isWhitespace(text.charAt(text.length() - 1))) {
            text.setLength(text.length() - 1);
        }
        if (text.length() > paragraphStart) {
            if (text.length() == TEXT_LIMIT) {
                throw tooLarge();
            }
            text.append('\n');
        }
        paragraphStart = text.length();
        captionParagraph = false;
    }

    private boolean minusHasDigits() throws IOException {
        input.mark(1);
        int afterMinus = input.read();
        input.reset();
        return digit(afterMinus);
    }

    private int readNumber() throws IOException {
        boolean negative = peek() == '-';
        if (negative) {
            read();
        }
        if (!digit(peek())) {
            throw invalid();
        }
        long value = 0;
        while (digit(peek())) {
            value = value * 10 + read() - '0';
            if (value > (negative ? -(long) Integer.MIN_VALUE : Integer.MAX_VALUE)) {
                throw invalid();
            }
        }
        return (int) (negative ? -value : value);
    }

    private int peek() throws IOException {
        if (nextByte == -2) {
            nextByte = input.read();
            if (nextByte >= 0 && ++bytesRead > FILE_LIMIT) {
                throw tooLarge();
            }
        }
        return nextByte;
    }

    private int read() throws IOException {
        int value = peek();
        nextByte = -2;
        return value;
    }

    private static boolean white(int value) {
        return value == ' ' || value == '\t' || value == '\r' || value == '\n';
    }

    private static boolean digit(int value) {
        return value >= '0' && value <= '9';
    }

    private static boolean letter(int value) {
        return (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z');
    }

    private static int hex(int value) {
        if (digit(value)) {
            return value - '0';
        }
        if (value >= 'a' && value <= 'f') {
            return value - 'a' + 10;
        }
        if (value >= 'A' && value <= 'F') {
            return value - 'A' + 10;
        }
        return -1;
    }

    private static Charset charset(int codePage) {
        String name;
        switch (codePage) {
            case 65001: return StandardCharsets.UTF_8;
            case 10000: name = "x-MacRoman"; break;
            case 437: name = "IBM437"; break;
            case 850: name = "IBM850"; break;
            case 866: name = "IBM866"; break;
            case 874: name = "windows-874"; break;
            case 932: name = "windows-31j"; break;
            case 936: name = "GBK"; break;
            case 949: name = "x-windows-949"; break;
            case 950: name = "Big5"; break;
            case 1361: name = "Johab"; break;
            default:
                if (codePage < 1250 || codePage > 1258) {
                    return WESTERN;
                }
                name = "windows-" + codePage;
                break;
        }
        try {
            return Charset.forName(name);
        } catch (IllegalArgumentException unavailable) {
            return WESTERN;
        }
    }

    private static int familyCodePage(int family) {
        switch (family) {
            case 77: return 10000;
            case 128: return 932;
            case 129: return 949;
            case 130: return 1361;
            case 134: return 936;
            case 136: return 950;
            case 161: return 1253;
            case 162: return 1254;
            case 163: return 1258;
            case 177: return 1255;
            case 178: return 1256;
            case 186: return 1257;
            case 204: return 1251;
            case 222: return 874;
            case 238: return 1250;
            case 255: return 437;
            default: return 0;
        }
    }

    private static IOException invalid() {
        return new IOException("Не удалось прочитать документ RTF: файл повреждён или имеет другой формат.");
    }

    private static IOException tooLarge() {
        return new IOException("Документ RTF слишком большой для импорта.");
    }
}
