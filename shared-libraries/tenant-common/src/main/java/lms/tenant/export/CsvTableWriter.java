package lms.tenant.export;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

/**
 * Ghi CSV theo hợp đồng ADR-0031: UTF-8 có BOM, phân tách dấu phẩy, RFC 4180 (bao nháy kép khi có dấu phẩy, nháy kép,
 * xuống dòng; nháy kép nhân đôi), xuống dòng {@code \r\n}, null là ô rỗng. Writer không đổi nội dung ô; việc vô hiệu hóa
 * công thức bảng tính ({@code = + - @} ở đầu ô văn bản) do {@link JdbcTenantTableReader#neutralizeFormula} làm khi đọc.
 */
public final class CsvTableWriter {

    public static final char BOM = '﻿';

    private final Writer out;

    /** Ghi BOM ngay khi dựng; {@code out} phải mã hóa UTF-8. */
    public CsvTableWriter(Writer out) throws IOException {
        this.out = out;
        out.write(BOM);
    }

    public void writeRow(List<String> cells) throws IOException {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) out.write(',');
            writeCell(cells.get(i));
        }
        out.write("\r\n");
    }

    private void writeCell(String value) throws IOException {
        if (value == null || value.isEmpty()) return;
        if (!needsQuote(value)) {
            out.write(value);
            return;
        }
        out.write('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') out.write('"');
            out.write(c);
        }
        out.write('"');
    }

    private static boolean needsQuote(String v) {
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == ',' || c == '"' || c == '\r' || c == '\n') return true;
        }
        return v.charAt(0) == ' ' || v.charAt(v.length() - 1) == ' ';
    }

    public void flush() throws IOException {
        out.flush();
    }
}
