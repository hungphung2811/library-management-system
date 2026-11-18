package lms.imports.core;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Schema của một loại dữ liệu nhập.
 *
 * @param type            mã loại (ví dụ {@code titles}); ghi vào sheet ẩn {@code _meta} và đường dẫn API
 * @param templateVersion phiên bản mẫu
 * @param sheets          các sheet dữ liệu (thứ tự = thứ tự trong file mẫu)
 */
public record ImportSchema(String type, int templateVersion, List<SheetSpec> sheets) {

    public ImportSchema {
        if (type == null || type.isBlank()) throw new IllegalArgumentException("type");
        if (sheets == null || sheets.isEmpty()) throw new IllegalArgumentException("sheets");
        sheets = List.copyOf(sheets);
        Set<String> names = new HashSet<>();
        for (SheetSpec s : sheets) {
            if (!names.add(s.name().toLowerCase(java.util.Locale.ROOT))) throw new IllegalArgumentException("Trùng sheet: " + s.name());
        }
    }

    public static ImportSchema single(String type, int templateVersion, String sheetName, List<ColumnSpec> columns) {
        return new ImportSchema(type, templateVersion, List.of(new SheetSpec(sheetName, true, columns)));
    }

    public SheetSpec sheet(String name) {
        for (SheetSpec s : sheets) if (s.name().equalsIgnoreCase(name)) return s;
        return null;
    }
}
