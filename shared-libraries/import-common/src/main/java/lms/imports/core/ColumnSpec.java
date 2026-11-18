package lms.imports.core;

import java.util.List;

/**
 * Khai báo một cột của file nhập.
 *
 * @param key        khóa kỹ thuật (tên trường trong {@code ImportRow.values})
 * @param label      nhãn tiếng Việt ở dòng tiêu đề (không kèm dấu {@code *}; thư viện tự thêm cho cột bắt buộc)
 * @param required   bắt buộc có giá trị
 * @param type       kiểu cú pháp
 * @param maxLength  độ dài tối đa (0 = không giới hạn riêng, vẫn chịu giới hạn ô chung)
 * @param example    giá trị ví dụ cho sheet hướng dẫn
 * @param hint       mô tả/quy tắc hiển thị ở sheet hướng dẫn
 * @param enumValues các giá trị hợp lệ (kiểu ENUM; dùng làm dropdown)
 */
public record ColumnSpec(String key, String label, boolean required, ColumnType type, int maxLength, String example,
                         String hint, List<String> enumValues) {

    public ColumnSpec {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key");
        if (label == null || label.isBlank()) throw new IllegalArgumentException("label");
        type = type == null ? ColumnType.TEXT : type;
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
        example = example == null ? "" : example;
        hint = hint == null ? "" : hint;
    }

    public static ColumnSpec text(String key, String label, boolean required, int maxLength) {
        return new ColumnSpec(key, label, required, ColumnType.TEXT, maxLength, "", "", List.of());
    }

    public static ColumnSpec of(String key, String label, boolean required, ColumnType type) {
        return new ColumnSpec(key, label, required, type, 0, "", "", List.of());
    }

    public static ColumnSpec enumeration(String key, String label, boolean required, List<String> values) {
        return new ColumnSpec(key, label, required, ColumnType.ENUM, 0, values.isEmpty() ? "" : values.get(0), "", values);
    }

    public ColumnSpec withExample(String value) {
        return new ColumnSpec(key, label, required, type, maxLength, value, hint, enumValues);
    }

    public ColumnSpec withHint(String value) {
        return new ColumnSpec(key, label, required, type, maxLength, example, value, enumValues);
    }

    /** Cột giữ nguyên dạng văn bản trong Excel (định dạng {@code @}) để không đổi ISBN thành số mũ. */
    public boolean textFormatted() {
        return type == ColumnType.TEXT || type == ColumnType.LIST || type == ColumnType.ENUM;
    }
}
