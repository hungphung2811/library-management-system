package lms.sales.tenantdata.domain;

import java.util.regex.Pattern;

/** Một cột được xuất. Tên chỉ gồm {@code [a-z0-9_]} (đi thẳng vào câu SELECT nên phải an toàn). */
public record ExportColumn(String name, ColumnKind kind) {

    private static final Pattern IDENT = Pattern.compile("[a-z][a-z0-9_]*");

    public ExportColumn {
        if (name == null || !IDENT.matcher(name).matches()) {
            throw new IllegalArgumentException("Tên cột không hợp lệ: " + name);
        }
        if (kind == null) {
            throw new IllegalArgumentException("Thiếu kiểu cột " + name);
        }
    }
}
