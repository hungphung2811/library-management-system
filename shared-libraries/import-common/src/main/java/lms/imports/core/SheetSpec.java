package lms.imports.core;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Một sheet dữ liệu: dòng 1 là tiêu đề, dòng 2 trở đi là dữ liệu.
 *
 * @param name     tên sheet trong file (so khớp không phân biệt hoa thường/dấu cách thừa)
 * @param required thiếu sheet này thì file không đúng mẫu; sheet không bắt buộc có thể vắng (phải có ít nhất một sheet)
 */
public record SheetSpec(String name, boolean required, List<ColumnSpec> columns) {

    public SheetSpec {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name");
        if (columns == null || columns.isEmpty()) throw new IllegalArgumentException("columns");
        columns = List.copyOf(columns);
        Set<String> keys = new HashSet<>();
        for (ColumnSpec c : columns) {
            if (!keys.add(c.key())) throw new IllegalArgumentException("Trùng key cột: " + c.key());
        }
    }

    public ColumnSpec column(String key) {
        for (ColumnSpec c : columns) if (c.key().equals(key)) return c;
        return null;
    }
}
