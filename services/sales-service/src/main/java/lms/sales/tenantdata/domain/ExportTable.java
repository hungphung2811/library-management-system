package lms.sales.tenantdata.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Một bảng của sales_db được xuất ra {@code <name>.csv}: danh sách cột cố định (không dump schema thô), cột khóa dùng
 * để đọc theo con trỏ (keyset) và các cột bị loại có chủ đích. Khóa phải duy nhất trong phạm vi tenant, kiểu UUID hoặc
 * TEXT (so sánh {@code >} theo thứ tự của DB).
 */
public final class ExportTable {

    private static final Pattern IDENT = Pattern.compile("[a-z][a-z0-9_]*");

    private final String name;
    private final String keyColumn;
    private final List<ExportColumn> columns;
    private final Map<String, String> excludedColumns;
    private final int keyIndex;

    private ExportTable(String name, String keyColumn, List<ExportColumn> columns, Map<String, String> excludedColumns) {
        if (!IDENT.matcher(name).matches()) {
            throw new IllegalArgumentException("Tên bảng không hợp lệ: " + name);
        }
        this.name = name;
        this.keyColumn = keyColumn;
        this.columns = List.copyOf(columns);
        this.excludedColumns = Map.copyOf(excludedColumns);
        for (ExportColumn c : columns) {
            if (excludedColumns.containsKey(c.name())) {
                throw new IllegalArgumentException("Cột " + c.name() + " của " + name + " vừa xuất vừa bị loại");
            }
        }
        int idx = -1;
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equals(keyColumn)) {
                idx = i;
            }
        }
        if (idx < 0) {
            throw new IllegalArgumentException("Cột khóa " + keyColumn + " không nằm trong cột xuất của " + name);
        }
        ColumnKind keyKind = columns.get(idx).kind();
        if (keyKind != ColumnKind.UUID && keyKind != ColumnKind.TEXT) {
            throw new IllegalArgumentException("Cột khóa của " + name + " phải là uuid hoặc text");
        }
        this.keyIndex = idx;
    }

    /** {@code spec} mỗi phần tử dạng {@code "tên:kiểu"} (uuid, text, num, bool, ts, date, json). */
    public static Builder table(String name, String keyColumn, String... spec) {
        List<ExportColumn> cols = new ArrayList<>();
        for (String s : spec) {
            String[] p = s.split(":");
            if (p.length != 2) {
                throw new IllegalArgumentException("Đặc tả cột không hợp lệ: " + s);
            }
            cols.add(new ExportColumn(p[0], ColumnKind.parse(p[1])));
        }
        return new Builder(name, keyColumn, cols);
    }

    public static final class Builder {
        private final String name;
        private final String keyColumn;
        private final List<ExportColumn> columns;
        private final Map<String, String> excluded = new LinkedHashMap<>();

        private Builder(String name, String keyColumn, List<ExportColumn> columns) {
            this.name = name;
            this.keyColumn = keyColumn;
            this.columns = columns;
        }

        /** Cột có trong DB nhưng cố ý không xuất. */
        public Builder exclude(String column, String reason) {
            excluded.put(column, reason);
            return this;
        }

        public ExportTable build() {
            return new ExportTable(name, keyColumn, columns, excluded);
        }
    }

    public String name() {
        return name;
    }

    public String file() {
        return name + ".csv";
    }

    public String keyColumn() {
        return keyColumn;
    }

    public int keyIndex() {
        return keyIndex;
    }

    public ColumnKind keyKind() {
        return columns.get(keyIndex).kind();
    }

    public List<ExportColumn> columns() {
        return columns;
    }

    /** Cột bị loại -> lý do. */
    public Map<String, String> excludedColumns() {
        return excludedColumns;
    }
}
