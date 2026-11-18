package lms.imports.xlsx;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import lms.imports.core.ColumnSpec;
import lms.imports.core.ImportErrorCode;
import lms.imports.core.ImportException;
import lms.imports.core.ImportLimits;
import lms.imports.core.ImportRow;
import lms.imports.core.ImportSchema;
import lms.imports.core.IssuedCredential;
import lms.imports.core.RowIssue;
import lms.imports.core.RowStatus;
import lms.imports.core.SheetSpec;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/**
 * Ghi bằng SXSSF (cửa sổ dòng, phần còn lại ra file tạm). Chống Excel injection: mọi ô là chuỗi ({@code setCellValue(String)},
 * không {@code setCellFormula}); ô bắt đầu bằng {@code = + - @ tab CR} dùng style {@code quotePrefixed} (Excel hiển thị như
 * văn bản) mà KHÔNG chèn dấu nháy vào nội dung, nên sửa file lỗi rồi upload lại vẫn ra đúng giá trị gốc. Không tạo hyperlink.
 */
public final class SxssfXlsxWriter implements XlsxWriter {

    private static final int WINDOW = 100;
    private static final String META_SHEET = "_meta";
    public static final String STATUS_LABEL = "Trạng thái";
    public static final String ERROR_LABEL = "Lỗi";

    private final ImportLimits limits;

    public SxssfXlsxWriter(ImportLimits limits) {
        this.limits = limits;
    }

    @Override
    public byte[] template(ImportSchema schema) {
        return build(schema, wb -> {
            Styles st = new Styles(wb);
            guideSheet(wb, st, schema);
            for (SheetSpec spec : schema.sheets()) {
                Sheet sheet = wb.createSheet(spec.name());
                headerRow(sheet, st, spec, false);
                formatColumns(sheet, st, spec);
                addDropdowns(sheet, spec);
            }
            metaSheet(wb, st, schema);
        });
    }

    @Override
    public byte[] errorFile(ImportSchema schema, List<ImportRow> rows) {
        return build(schema, wb -> {
            Styles st = new Styles(wb);
            for (SheetSpec spec : schema.sheets()) {
                Sheet sheet = wb.createSheet(spec.name());
                headerRow(sheet, st, spec, true);
                formatColumns(sheet, st, spec);
                int r = 1;
                for (ImportRow row : rows) {
                    if (!row.sheet().equals(spec.name())) continue;
                    Row out = sheet.createRow(r++);
                    int c = 0;
                    for (ColumnSpec col : spec.columns()) {
                        put(out, c++, row.value(col.key()), st);
                    }
                    put(out, c++, statusText(row.status()), st);
                    put(out, c, issuesText(spec, row.issues()), st);
                }
            }
            metaSheet(wb, st, schema);
        });
    }

    @Override
    public byte[] credentials(List<IssuedCredential> credentials) {
        SXSSFWorkbook wb = new SXSSFWorkbook(WINDOW);
        wb.setCompressTempFiles(true);
        try {
            Styles st = new Styles(wb);
            Sheet sheet = wb.createSheet("MatKhauTam");
            Row head = sheet.createRow(0);
            putHeader(head, 0, "Tên đăng nhập", st);
            putHeader(head, 1, "Mật khẩu tạm", st);
            sheet.setDefaultColumnStyle(0, st.text);
            sheet.setDefaultColumnStyle(1, st.text);
            sheet.setColumnWidth(0, 30 * 256);
            sheet.setColumnWidth(1, 30 * 256);
            int r = 1;
            for (IssuedCredential c : credentials) {
                Row row = sheet.createRow(r++);
                put(row, 0, c.login(), st);
                put(row, 1, c.secret(), st);
            }
            return toBytes(wb);
        } finally {
            wb.dispose();
        }
    }

    // ------------------------------------------------------------------ dựng sheet

    private interface Body {
        void accept(SXSSFWorkbook wb);
    }

    private byte[] build(ImportSchema schema, Body body) {
        SXSSFWorkbook wb = new SXSSFWorkbook(WINDOW);
        wb.setCompressTempFiles(true);
        try {
            body.accept(wb);
            return toBytes(wb);
        } finally {
            wb.dispose();
        }
    }

    private static byte[] toBytes(SXSSFWorkbook wb) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new ImportException(ImportErrorCode.FILE_CORRUPT, "Không ghi được file Excel", List.of(), e);
        }
    }

    private void guideSheet(SXSSFWorkbook wb, Styles st, ImportSchema schema) {
        Sheet sheet = wb.createSheet("HuongDan");
        String[] heads = {"Sheet", "Cột", "Bắt buộc", "Kiểu", "Quy tắc / giá trị hợp lệ", "Ví dụ"};
        Row h = sheet.createRow(0);
        for (int i = 0; i < heads.length; i++) putHeader(h, i, heads[i], st);
        int r = 1;
        for (SheetSpec spec : schema.sheets()) {
            for (ColumnSpec c : spec.columns()) {
                Row row = sheet.createRow(r++);
                put(row, 0, spec.name(), st);
                put(row, 1, c.label(), st);
                put(row, 2, c.required() ? "Có" : "Không", st);
                put(row, 3, c.type().name(), st);
                String rule = c.hint();
                if (!c.enumValues().isEmpty()) {
                    rule = (rule.isEmpty() ? "" : rule + ". ") + "Giá trị: " + String.join(", ", c.enumValues());
                }
                if (c.maxLength() > 0) rule = (rule.isEmpty() ? "" : rule + ". ") + "Tối đa " + c.maxLength() + " ký tự";
                put(row, 4, rule, st);
                put(row, 5, c.example(), st);
            }
        }
        int[] widths = {16, 26, 10, 12, 60, 24};
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);
    }

    private void headerRow(Sheet sheet, Styles st, SheetSpec spec, boolean withErrorColumns) {
        Row row = sheet.createRow(0);
        int c = 0;
        for (ColumnSpec col : spec.columns()) {
            putHeader(row, c, col.required() ? col.label() + " *" : col.label(), st);
            sheet.setColumnWidth(c, Math.min(40, Math.max(14, col.label().length() + 6)) * 256);
            c++;
        }
        if (withErrorColumns) {
            putHeader(row, c, STATUS_LABEL, st);
            sheet.setColumnWidth(c++, 16 * 256);
            putHeader(row, c, ERROR_LABEL, st);
            sheet.setColumnWidth(c, 60 * 256);
        }
    }

    /** Định dạng {@code @} (Text) cho cột văn bản để Excel không đổi ISBN thành số mũ. */
    private void formatColumns(Sheet sheet, Styles st, SheetSpec spec) {
        int c = 0;
        for (ColumnSpec col : spec.columns()) {
            if (col.textFormatted()) sheet.setDefaultColumnStyle(c, st.text);
            c++;
        }
    }

    private void addDropdowns(Sheet sheet, SheetSpec spec) {
        DataValidationHelper helper = sheet.getDataValidationHelper();
        int c = 0;
        for (ColumnSpec col : spec.columns()) {
            if (!col.enumValues().isEmpty()) {
                String[] values = col.enumValues().toArray(new String[0]);
                DataValidationConstraint constraint = helper.createExplicitListConstraint(values);
                CellRangeAddressList range = new CellRangeAddressList(1, limits.maxRows() + 1, c, c);
                DataValidation validation = helper.createValidation(constraint, range);
                validation.setShowErrorBox(true);
                validation.setSuppressDropDownArrow(true);
                sheet.addValidationData(validation);
            }
            c++;
        }
    }

    private void metaSheet(SXSSFWorkbook wb, Styles st, ImportSchema schema) {
        Sheet sheet = wb.createSheet(META_SHEET);
        Row a = sheet.createRow(0);
        put(a, 0, "type", st);
        put(a, 1, schema.type(), st);
        Row b = sheet.createRow(1);
        put(b, 0, "templateVersion", st);
        put(b, 1, Integer.toString(schema.templateVersion()), st);
        wb.setSheetVisibility(wb.getSheetIndex(META_SHEET), SheetVisibility.HIDDEN);
    }

    private static String statusText(RowStatus status) {
        return switch (status) {
            case VALID -> "Hợp lệ";
            case INVALID -> "Lỗi";
            case DUPLICATE -> "Trùng";
        };
    }

    private static String issuesText(SheetSpec spec, List<RowIssue> issues) {
        List<String> parts = new ArrayList<>();
        for (RowIssue i : issues) {
            ColumnSpec col = i.column() == null ? null : spec.column(i.column());
            parts.add(col == null ? i.message() : col.label() + ": " + i.message());
        }
        return String.join("; ", parts);
    }

    // ------------------------------------------------------------------ ô

    private static void putHeader(Row row, int col, String text, Styles st) {
        Cell cell = row.createCell(col);
        cell.setCellValue(text);
        cell.setCellStyle(st.header);
    }

    /** Luôn ô chuỗi; quotePrefixed nếu giá trị bắt đầu bằng ký tự công thức. */
    private static void put(Row row, int col, String value, Styles st) {
        Cell cell = row.createCell(col);
        String v = value == null ? "" : value;
        if (v.length() > 32_000) v = v.substring(0, 32_000);
        cell.setCellValue(v);
        cell.setCellStyle(CellSanitizer.needsQuotePrefix(v) ? st.quoted : st.text);
    }

    private static final class Styles {
        final CellStyle header;
        final CellStyle text;
        final CellStyle quoted;

        Styles(SXSSFWorkbook wb) {
            short textFormat = wb.createDataFormat().getFormat("@");
            text = wb.createCellStyle();
            text.setDataFormat(textFormat);
            quoted = wb.createCellStyle();
            quoted.setDataFormat(textFormat);
            quoted.setQuotePrefixed(true);
            header = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            header.setFont(bold);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setDataFormat(textFormat);
        }
    }
}
