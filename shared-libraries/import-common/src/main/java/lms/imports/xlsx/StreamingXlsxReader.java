package lms.imports.xlsx;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lms.imports.core.ColumnSpec;
import lms.imports.core.ImportErrorCode;
import lms.imports.core.ImportException;
import lms.imports.core.ImportLimits;
import lms.imports.core.ImportRow;
import lms.imports.core.ImportSchema;
import lms.imports.core.IssueCodes;
import lms.imports.core.RowIssue;
import lms.imports.core.RowStatus;
import lms.imports.core.SheetSpec;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ooxml.util.SAXHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Đọc .xlsx bằng SAX (XSSFReader), không dựng DOM workbook. Chỉ đọc GIÁ TRỊ LƯU SẴN của ô (không tính công thức; ô công
 * thức không có giá trị lưu sẵn là trống). Số thành chuỗi phẳng ({@code BigDecimal.toPlainString}), ngày Excel thành
 * {@code yyyy-MM-dd}, boolean thành {@code true/false}.
 */
public final class StreamingXlsxReader implements XlsxReader {

    private static final String META_SHEET = "_meta";

    private final ImportLimits limits;
    private final XlsxSafetyInspector inspector;

    public StreamingXlsxReader(ImportLimits limits) {
        this.limits = limits;
        this.inspector = new XlsxSafetyInspector(limits);
        // Chặn zip bomb ở tầng POI (đếm byte thật khi POI giải nén); trạng thái toàn cục của POI.
        ZipSecureFile.setMinInflateRatio(limits.minInflateRatio());
        ZipSecureFile.setMaxEntrySize(limits.maxEntryBytes());
        ZipSecureFile.setMaxTextSize(limits.maxEntryBytes());
    }

    @Override
    public List<ImportRow> read(byte[] data, ImportSchema schema) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("lms-import-", ".xlsx");
            Files.write(tmp, data);
            inspector.inspect(tmp);
            return parse(tmp, schema);
        } catch (ImportException e) {
            throw e;
        } catch (Exception e) {
            Throwable root = unwrap(e);
            if (root instanceof ImportException ie) throw ie;
            String msg = String.valueOf(root.getMessage()).toLowerCase(Locale.ROOT);
            if (msg.contains("zip bomb") || msg.contains("maximum") || msg.contains("too large") || msg.contains("doctype")
                    || msg.contains("dtd") || msg.contains("entity")) {
                throw new ImportException(ImportErrorCode.FILE_UNSAFE, ImportErrorCode.FILE_UNSAFE.defaultMessage(), List.of(), e);
            }
            throw new ImportException(ImportErrorCode.FILE_CORRUPT, ImportErrorCode.FILE_CORRUPT.defaultMessage(), List.of(), e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // file tạm sẽ do hệ điều hành dọn
                }
            }
        }
    }

    private static Throwable unwrap(Throwable t) {
        Throwable cur = t;
        for (int i = 0; i < 8 && cur != null; i++) {
            if (cur instanceof ImportException) return cur;
            if (cur.getCause() == null || cur.getCause() == cur) break;
            cur = cur.getCause();
        }
        return t;
    }

    private List<ImportRow> parse(Path file, ImportSchema schema) throws Exception {
        List<ImportRow> rows = new ArrayList<>();
        List<String> seenSheets = new ArrayList<>();
        try (OPCPackage pkg = OPCPackage.open(file.toFile(), PackageAccess.READ)) {
            XSSFReader reader = new XSSFReader(pkg);
            ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg, false);
            StylesTable styles = reader.getStylesTable();
            XSSFReader.SheetIterator it = (XSSFReader.SheetIterator) reader.getSheetsData();
            while (it.hasNext()) {
                try (InputStream in = it.next()) {
                    String name = it.getSheetName();
                    String normalized = CellSanitizer.normalizeHeader(name);
                    if (normalized.equals(META_SHEET)) {
                        checkMeta(in, strings, styles, schema);
                        continue;
                    }
                    SheetSpec spec = findSheet(schema, normalized);
                    if (spec == null) continue;
                    seenSheets.add(spec.name());
                    SheetHandler handler = new SheetHandler(spec, strings, styles, rows.size());
                    parseXml(in, handler);
                    if (!handler.headerDone) {
                        throw new ImportException(ImportErrorCode.TEMPLATE_MISMATCH,
                                ImportErrorCode.TEMPLATE_MISMATCH.defaultMessage(),
                                List.of("Sheet " + spec.name() + ": thiếu dòng tiêu đề ở dòng 1"));
                    }
                    rows.addAll(handler.rows);
                }
            }
        }
        List<String> missing = new ArrayList<>();
        for (SheetSpec s : schema.sheets()) {
            if (s.required() && !seenSheets.contains(s.name())) missing.add("Thiếu sheet: " + s.name());
        }
        if (!missing.isEmpty() || seenSheets.isEmpty()) {
            if (missing.isEmpty()) missing.add("Không có sheet dữ liệu nào đúng mẫu");
            throw new ImportException(ImportErrorCode.TEMPLATE_MISMATCH, ImportErrorCode.TEMPLATE_MISMATCH.defaultMessage(), missing);
        }
        if (rows.isEmpty()) throw new ImportException(ImportErrorCode.EMPTY);
        return rows;
    }

    private static SheetSpec findSheet(ImportSchema schema, String normalizedName) {
        for (SheetSpec s : schema.sheets()) {
            if (CellSanitizer.normalizeHeader(s.name()).equals(normalizedName)) return s;
        }
        return null;
    }

    private void checkMeta(InputStream in, ReadOnlySharedStringsTable strings, StylesTable styles, ImportSchema schema)
            throws Exception {
        MetaHandler handler = new MetaHandler(strings, styles);
        parseXml(in, handler);
        String type = handler.values.get("type");
        if (type != null && !type.equals(schema.type())) {
            throw new ImportException(ImportErrorCode.TEMPLATE_MISMATCH, "File thuộc loại dữ liệu khác",
                    List.of("Mẫu loại: " + CellSanitizer.sanitizeFileName(type)));
        }
    }

    private static void parseXml(InputStream in, DefaultHandler handler) throws Exception {
        XMLReader xr = SAXHelper.newXMLReader();
        try {
            xr.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (SAXException ignored) {
            // parser không hỗ trợ cờ này: XMLHelper của POI vẫn tắt entity ngoài, và inspector đã chặn DOCTYPE
        }
        xr.setContentHandler(handler);
        xr.parse(new InputSource(in));
    }

    // ------------------------------------------------------------------ SAX cho một sheet

    /** Đọc ô theo tọa độ; lớp con quyết định xử lý khi hết một dòng. */
    private abstract class CellReader extends DefaultHandler {
        private final ReadOnlySharedStringsTable strings;
        private final StylesTable styles;
        private final Map<Integer, Boolean> dateStyles = new HashMap<>();

        int rowNum;
        int lastCol;
        String[] cells = new String[limits.maxColumns()];
        boolean[] tooLong = new boolean[limits.maxColumns()];
        boolean anyCell;

        private int col;
        private String type;
        private int style;
        private boolean inV;
        private boolean inT;
        private boolean inIs;
        private final StringBuilder text = new StringBuilder();
        private boolean overflow;

        CellReader(ReadOnlySharedStringsTable strings, StylesTable styles) {
            this.strings = strings;
            this.styles = styles;
        }

        abstract void endRow();

        @Override
        public void startElement(String uri, String local, String qName, Attributes atts) {
            String n = local == null || local.isEmpty() ? qName : local;
            switch (n) {
                case "row" -> {
                    String r = atts.getValue("r");
                    rowNum = r != null ? parseInt(r, rowNum + 1) : rowNum + 1;
                    java.util.Arrays.fill(cells, null);
                    java.util.Arrays.fill(tooLong, false);
                    anyCell = false;
                    lastCol = -1;
                }
                case "c" -> {
                    String ref = atts.getValue("r");
                    col = ref != null ? columnIndex(ref) : lastCol + 1;
                    lastCol = col;
                    type = atts.getValue("t");
                    style = parseInt(atts.getValue("s"), -1);
                    text.setLength(0);
                    overflow = false;
                    inV = false;
                    inT = false;
                    inIs = false;
                }
                case "v" -> inV = true;
                case "is" -> inIs = true;
                case "t" -> inT = inIs;
                default -> { }
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (!(inV || inT)) return;
            int room = limits.maxCellChars() + 1 - text.length();
            if (room <= 0) {
                overflow = true;
                return;
            }
            if (length > room) {
                text.append(ch, start, room);
                overflow = true;
            } else {
                text.append(ch, start, length);
            }
        }

        @Override
        public void endElement(String uri, String local, String qName) {
            String n = local == null || local.isEmpty() ? qName : local;
            switch (n) {
                case "v" -> inV = false;
                case "t" -> inT = false;
                case "is" -> inIs = false;
                case "c" -> finishCell();
                case "row" -> endRow();
                default -> { }
            }
        }

        private void finishCell() {
            if (col < 0 || col >= cells.length) return;
            String value = resolve();
            if (value == null) return;
            if (overflow || value.length() > limits.maxCellChars()) {
                tooLong[col] = true;
                if (value.length() > limits.maxCellChars()) value = value.substring(0, limits.maxCellChars());
            }
            String normalized = CellSanitizer.normalize(value);
            cells[col] = normalized;
            if (!normalized.isEmpty()) anyCell = true;
        }

        private String resolve() {
            String raw = text.toString();
            String t = type == null ? "n" : type;
            switch (t) {
                case "s" -> {
                    int idx = parseInt(raw.trim(), -1);
                    if (idx < 0) return null;
                    try {
                        return strings.getItemAt(idx).getString();
                    } catch (RuntimeException e) {
                        return null;
                    }
                }
                case "str", "inlineStr" -> {
                    return raw;
                }
                case "b" -> {
                    return "1".equals(raw.trim()) ? "true" : "0".equals(raw.trim()) ? "false" : null;
                }
                case "e" -> {
                    return null;
                }
                case "d" -> {
                    String s = raw.trim();
                    return s.length() >= 10 ? s.substring(0, 10) : s;
                }
                default -> {
                    String s = raw.trim();
                    if (s.isEmpty()) return null;
                    try {
                        if (isDateStyle(style)) {
                            LocalDate d = DateUtil.getLocalDateTime(Double.parseDouble(s)).toLocalDate();
                            return d.toString();
                        }
                        return new BigDecimal(s).stripTrailingZeros().toPlainString();
                    } catch (RuntimeException e) {
                        return s;
                    }
                }
            }
        }

        private boolean isDateStyle(int idx) {
            if (idx < 0) return false;
            return dateStyles.computeIfAbsent(idx, i -> {
                try {
                    XSSFCellStyle st = styles.getStyleAt(i);
                    return st != null && DateUtil.isADateFormat(st.getDataFormat(), st.getDataFormatString());
                } catch (RuntimeException e) {
                    return false;
                }
            });
        }
    }

    private final class SheetHandler extends CellReader {
        private final SheetSpec spec;
        private final int rowsBefore;
        final List<ImportRow> rows = new ArrayList<>();
        private final Map<Integer, ColumnSpec> byCol = new LinkedHashMap<>();
        boolean headerDone;

        SheetHandler(SheetSpec spec, ReadOnlySharedStringsTable strings, StylesTable styles, int rowsBefore) {
            super(strings, styles);
            this.spec = spec;
            this.rowsBefore = rowsBefore;
        }

        @Override
        void endRow() {
            if (!headerDone) {
                if (rowNum != 1) return; // chỉ dòng 1 là tiêu đề; dòng trống trước đó bị bỏ qua
                readHeader();
                headerDone = true;
                return;
            }
            if (rowNum < 2 || !anyCell) return;
            Map<String, String> values = new LinkedHashMap<>();
            List<RowIssue> issues = new ArrayList<>();
            boolean any = false;
            for (Map.Entry<Integer, ColumnSpec> e : byCol.entrySet()) {
                int c = e.getKey();
                String v = cells[c] == null ? "" : cells[c];
                if (!v.isEmpty()) any = true;
                values.put(e.getValue().key(), v);
                if (tooLong[c]) {
                    issues.add(RowIssue.error(e.getValue().key(), IssueCodes.CELL_TOO_LONG,
                            "Ô vượt quá " + limits.maxCellChars() + " ký tự"));
                }
            }
            if (!any) return;
            if (rowsBefore + rows.size() + 1 > limits.maxRows()) {
                throw new ImportException(ImportErrorCode.TOO_MANY_ROWS,
                        "File vượt quá " + limits.maxRows() + " dòng dữ liệu");
            }
            rows.add(new ImportRow(spec.name(), rowNum, values, issues.isEmpty() ? RowStatus.VALID : RowStatus.INVALID,
                    issues, Map.of()));
        }

        private void readHeader() {
            Map<String, ColumnSpec> index = new HashMap<>();
            for (ColumnSpec c : spec.columns()) {
                index.putIfAbsent(CellSanitizer.normalizeHeader(c.label()), c);
                index.putIfAbsent(CellSanitizer.normalizeHeader(c.key()), c);
            }
            List<String> used = new ArrayList<>();
            for (int i = 0; i < cells.length; i++) {
                if (cells[i] == null || cells[i].isEmpty()) continue;
                ColumnSpec c = index.get(CellSanitizer.normalizeHeader(cells[i]));
                if (c != null && !used.contains(c.key())) {
                    byCol.put(i, c);
                    used.add(c.key());
                }
            }
            List<String> missing = new ArrayList<>();
            for (ColumnSpec c : spec.columns()) {
                if (c.required() && !used.contains(c.key())) missing.add("Sheet " + spec.name() + ": thiếu cột " + c.label());
            }
            if (!missing.isEmpty()) {
                throw new ImportException(ImportErrorCode.TEMPLATE_MISMATCH,
                        ImportErrorCode.TEMPLATE_MISMATCH.defaultMessage(), missing);
            }
        }
    }

    /** Sheet {@code _meta}: cột A là khóa, cột B là giá trị. */
    private final class MetaHandler extends CellReader {
        final Map<String, String> values = new HashMap<>();

        MetaHandler(ReadOnlySharedStringsTable strings, StylesTable styles) {
            super(strings, styles);
        }

        @Override
        void endRow() {
            if (rowNum > 20 || cells.length < 2) return;
            String k = cells[0];
            String v = cells[1];
            if (k != null && !k.isEmpty() && v != null) values.put(k, v);
        }
    }

    // ------------------------------------------------------------------ tiện ích

    private static int parseInt(String s, int fallback) {
        if (s == null || s.isEmpty()) return fallback;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** "AB12" thành chỉ số cột 0-based (A=0). */
    static int columnIndex(String ref) {
        int col = 0;
        for (int i = 0; i < ref.length(); i++) {
            char c = ref.charAt(i);
            if (c >= 'A' && c <= 'Z') col = col * 26 + (c - 'A' + 1);
            else if (c >= 'a' && c <= 'z') col = col * 26 + (c - 'a' + 1);
            else break;
        }
        return col - 1;
    }
}
