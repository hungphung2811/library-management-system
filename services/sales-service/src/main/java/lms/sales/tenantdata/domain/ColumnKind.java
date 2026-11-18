package lms.sales.tenantdata.domain;

import java.util.Locale;

/** Kiểu cột khi xuất CSV (ADR-0031 Phụ lục A): quyết định cách đọc từ DB và cách ghi ra ô. */
public enum ColumnKind {
    UUID, TEXT, NUMBER, BOOLEAN, TIMESTAMP, DATE, JSON;

    public static ColumnKind parse(String token) {
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "uuid" -> UUID;
            case "text" -> TEXT;
            case "num" -> NUMBER;
            case "bool" -> BOOLEAN;
            case "ts" -> TIMESTAMP;
            case "date" -> DATE;
            case "json" -> JSON;
            default -> throw new IllegalArgumentException("Kiểu cột không hợp lệ: " + token);
        };
    }
}
