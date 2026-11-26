package com.audiokniga.app;

import android.content.Context;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.contentstream.operator.Operator;
import com.tom_roush.pdfbox.cos.COSBase;
import com.tom_roush.pdfbox.cos.COSDictionary;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDResources;
import com.tom_roush.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkedContentReference;
import com.tom_roush.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import com.tom_roush.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureNode;
import com.tom_roush.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import com.tom_roush.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList;
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException;
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;

import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class PdfBookParser {
    private static final int MIB = 1024 * 1024;
    private static final int MAX_FILE_BYTES = 64 * MIB;
    private static final int MAX_TEXT_LENGTH = 16 * MIB;
    private static final int MAX_PAGES = 10_000;
    private static final int MAX_GLYPHS_PER_PAGE = 180_000;
    private static final int MAX_STRUCTURE_NODES = 80_000;
    private static final int MAX_MARKED_DEPTH = 256;
    private static final COSName MARKED_STREAM = COSName.getPDFName("Stm");

    private static volatile File cacheDirectory;

    private PdfBookParser() {}

    static void initialize(Context context) {
        Context app = context.getApplicationContext();
        PDFBoxResourceLoader.init(app);
        cacheDirectory = app.getCacheDir();
    }

    static BookParser.ParsedBook parse(InputStream input) throws IOException {
        MemoryUsageSetting memory = MemoryUsageSetting.setupMixed(8L * MIB, 192L * MIB);
        if (cacheDirectory != null) {
            memory.setTempDir(cacheDirectory);
        }
        try (PDDocument document = PDDocument.load(new PdfInput(input), memory)) {
            if (!document.getCurrentAccessPermission().canExtractContent()) {
                throw new PdfImportException("В этом PDF запрещено извлечение текста.");
            }
            if (document.getNumberOfPages() > MAX_PAGES) {
                throw sizeLimit();
            }

            PdfTextWriter output = new PdfTextWriter();
            CaptionSkippingStripper stripper = new CaptionSkippingStripper(
                    document.getDocumentCatalog().getStructureTreeRoot());
            stripper.writeText(document, output);
            String text = output.text.toString().trim();
            if (text.isEmpty()) {
                throw new PdfImportException("В PDF нет текстового слоя для чтения. "
                        + "Для PDF со сканами сначала распознайте текст.");
            }
            return new BookParser.ParsedBook(document.getDocumentInformation().getTitle(), text);
        } catch (InvalidPasswordException exception) {
            throw new IOException("PDF защищён паролем. Откройте файл без пароля для импорта.", exception);
        } catch (PdfImportException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new IOException("Не удалось извлечь текст из PDF. Проверьте, что файл открывается.", exception);
        }
    }

    private static PdfImportException sizeLimit() {
        return new PdfImportException("PDF слишком большой для импорта.");
    }

    private static final class PdfImportException extends IOException {
        PdfImportException(String message) {
            super(message);
        }
    }

    private static final class PdfInput extends FilterInputStream {
        private int bytesRead;

        PdfInput(InputStream input) {
            super(input);
        }

        @Override public int read() throws IOException {
            int value = in.read();
            if (value >= 0 && ++bytesRead > MAX_FILE_BYTES) {
                throw sizeLimit();
            }
            return value;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            int allowed = MAX_FILE_BYTES - bytesRead;
            if (allowed == 0) {
                return read();
            }
            int count = in.read(bytes, offset, Math.min(length, allowed));
            if (count > 0) {
                bytesRead += count;
            }
            return count;
        }

        @Override public long skip(long count) throws IOException {
            long skipped = in.skip(Math.min(count, MAX_FILE_BYTES - bytesRead));
            bytesRead += (int) skipped;
            return skipped;
        }

        @Override public boolean markSupported() {
            return false;
        }

        @Override public void reset() throws IOException {
            throw new IOException("PDF input cannot be reset.");
        }

        @Override public void close() {
            // The import caller owns the input stream.
        }
    }

    private static final class PdfTextWriter extends Writer {
        final StringBuilder text = new StringBuilder();

        @Override public void write(char[] input, int offset, int count) throws IOException {
            if (count > MAX_TEXT_LENGTH - text.length()) {
                throw sizeLimit();
            }
            text.append(input, offset, count);
        }

        @Override public void flush() {}

        @Override public void close() {}
    }

    private static final class CaptionSkippingStripper extends PDFTextStripper {
        private final Map<COSDictionary, Set<Integer>> excludedIds = new IdentityHashMap<>();
        private final Map<String, Object> roles;
        private COSDictionary scope;
        private ArrayDeque<Boolean> excluded = new ArrayDeque<>();
        private int pageCount;
        private int glyphCount;

        CaptionSkippingStripper(PDStructureTreeRoot structure) throws IOException {
            roles = structure == null ? Collections.emptyMap() : structure.getRoleMap();
            if (structure != null) {
                Set<COSDictionary> visited = Collections.newSetFromMap(new IdentityHashMap<>());
                collectCaptionIds(structure, null, false, visited, 0);
            }
            setSortByPosition(true);
            setLineSeparator("\n");
            setPageEnd("\n\n");
        }

        private void collectCaptionIds(PDStructureNode node, COSDictionary inheritedPage, boolean inCaption,
                                       Set<COSDictionary> visited, int depth) throws IOException {
            if (!visited.add(node.getCOSObject())) {
                return;
            }
            if (depth > MAX_MARKED_DEPTH || visited.size() > MAX_STRUCTURE_NODES) {
                throw sizeLimit();
            }
            COSDictionary page = inheritedPage;
            boolean skip = inCaption;
            if (node instanceof PDStructureElement) {
                PDStructureElement element = (PDStructureElement) node;
                PDPage definedPage = element.getPage();
                page = definedPage == null ? inheritedPage : definedPage.getCOSObject();
                skip |= isVisualLabel(element.getStructureType());
            }
            for (Object child : node.getKids()) {
                if (child instanceof PDStructureNode) {
                    collectCaptionIds((PDStructureNode) child, page, skip, visited, depth + 1);
                } else if (skip && child instanceof Integer) {
                    skipId(page, (Integer) child);
                } else if (skip && child instanceof PDMarkedContentReference) {
                    PDMarkedContentReference reference = (PDMarkedContentReference) child;
                    COSBase stream = reference.getCOSObject().getDictionaryObject(MARKED_STREAM);
                    PDPage referencedPage = reference.getPage();
                    COSDictionary scope = stream instanceof COSDictionary ? (COSDictionary) stream
                            : referencedPage == null ? page : referencedPage.getCOSObject();
                    skipId(scope, reference.getMCID());
                }
            }
        }

        private void skipId(COSDictionary stream, int id) {
            if (stream != null && id >= 0) {
                excludedIds.computeIfAbsent(stream, ignored -> new HashSet<>()).add(id);
            }
        }

        private boolean isVisualLabel(String kind) {
            Object mapped = roles.get(kind);
            String label = mapped instanceof String ? (String) mapped : kind;
            return "Caption".equals(label) || "Figure".equals(label) || "Artifact".equals(label);
        }

        @Override protected void startPage(PDPage page) throws IOException {
            if (++pageCount > MAX_PAGES) {
                throw sizeLimit();
            }
            glyphCount = 0;
            scope = page.getCOSObject();
            excluded.clear();
            super.startPage(page);
        }

        @Override protected void endPage(PDPage page) throws IOException {
            if (glyphCount > MAX_GLYPHS_PER_PAGE) {
                throw sizeLimit();
            }
            super.endPage(page);
        }

        @Override public void showForm(PDFormXObject form) throws IOException {
            if (isExcluded()) {
                return;
            }
            COSDictionary parentScope = scope;
            ArrayDeque<Boolean> parentFlags = excluded;
            scope = form.getCOSObject();
            excluded = new ArrayDeque<>();
            try {
                super.showForm(form);
            } finally {
                scope = parentScope;
                excluded = parentFlags;
            }
        }

        @Override protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            switch (operator.getName()) {
                case "BMC":
                case "BDC":
                    if (excluded.size() >= MAX_MARKED_DEPTH) {
                        throw sizeLimit();
                    }
                    excluded.push(isExcluded() || isCaptionOperator(operands));
                    break;
                case "EMC":
                    if (!excluded.isEmpty()) {
                        excluded.pop();
                    }
                    break;
                default:
                    super.processOperator(operator, operands);
                    break;
            }
        }

        private boolean isCaptionOperator(List<COSBase> operands) {
            if (!operands.isEmpty() && operands.get(0) instanceof COSName
                    && isVisualLabel(((COSName) operands.get(0)).getName())) {
                return true;
            }
            Set<Integer> ids = excludedIds.get(scope);
            if (ids == null || operands.size() < 2) {
                return false;
            }
            COSBase property = operands.get(1);
            if (property instanceof COSName) {
                PDResources resources = getResources();
                PDPropertyList named = resources == null ? null : resources.getProperties((COSName) property);
                property = named == null ? null : named.getCOSObject();
            }
            return property instanceof COSDictionary
                    && ids.contains(((COSDictionary) property).getInt(COSName.MCID));
        }

        private boolean isExcluded() {
            return !excluded.isEmpty() && excluded.peek();
        }

        @Override protected void processTextPosition(TextPosition position) {
            if (!isExcluded() && glyphCount <= MAX_GLYPHS_PER_PAGE && ++glyphCount <= MAX_GLYPHS_PER_PAGE) {
                super.processTextPosition(position);
            }
        }
    }
}
