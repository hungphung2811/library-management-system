package lms.imports.engine;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lms.imports.core.ColumnSpec;
import lms.imports.core.ImportRow;
import lms.imports.core.IssueCodes;
import lms.imports.core.RowIssue;
import lms.imports.core.RowStatus;
import lms.imports.core.SheetSpec;

/**
 * Kiểm cú pháp chung theo {@link ColumnSpec} (bắt buộc, độ dài, số, ngày, enum, boolean) và chuẩn hóa giá trị:
 * ngày thành {@code yyyy-MM-dd}, boolean thành {@code true/false}, enum về giá trị chuẩn, danh sách {@code a;b;c}.
 * Quy tắc nghiệp vụ không ở đây (handler kiểm bằng domain).
 */
public final class RowChecks {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter VN = DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT);

    private RowChecks() {
    }

    public static ImportRow check(SheetSpec spec, ImportRow row) {
        Map<String, String> values = new LinkedHashMap<>(row.values());
        List<RowIssue> issues = new ArrayList<>(row.issues());
        boolean error = row.status() == RowStatus.INVALID;
        for (ColumnSpec col : spec.columns()) {
            String v = values.getOrDefault(col.key(), "");
            if (v.isEmpty()) {
                if (col.required()) {
                    issues.add(RowIssue.error(col.key(), IssueCodes.REQUIRED, "Bắt buộc nhập"));
                    error = true;
                }
                continue;
            }
            if (col.maxLength() > 0 && v.length() > col.maxLength()) {
                issues.add(RowIssue.error(col.key(), IssueCodes.TOO_LONG, "Tối đa " + col.maxLength() + " ký tự"));
                error = true;
                continue;
            }
            String problem = null;
            String code = null;
            switch (col.type()) {
                case INTEGER -> {
                    try {
                        new BigInteger(v);
                    } catch (NumberFormatException e) {
                        code = IssueCodes.INVALID_NUMBER;
                        problem = "Phải là số nguyên";
                    }
                }
                case DECIMAL, MONEY -> {
                    try {
                        new BigDecimal(v);
                    } catch (NumberFormatException e) {
                        code = IssueCodes.INVALID_NUMBER;
                        problem = "Phải là số";
                    }
                }
                case DATE -> {
                    LocalDate d = parseDate(v);
                    if (d == null) {
                        code = IssueCodes.INVALID_DATE;
                        problem = "Ngày phải có dạng dd/MM/yyyy hoặc yyyy-MM-dd";
                    } else {
                        values.put(col.key(), d.toString());
                    }
                }
                case BOOLEAN -> {
                    Boolean b = parseBoolean(v);
                    if (b == null) {
                        code = IssueCodes.INVALID_BOOLEAN;
                        problem = "Chỉ nhận Có/Không, x, true/false";
                    } else {
                        values.put(col.key(), b.toString());
                    }
                }
                case ENUM -> {
                    String canonical = matchEnum(col, v);
                    if (canonical == null) {
                        code = IssueCodes.INVALID_ENUM;
                        problem = "Giá trị hợp lệ: " + String.join(", ", col.enumValues());
                    } else {
                        values.put(col.key(), canonical);
                    }
                }
                case LIST -> values.put(col.key(), normalizeList(v));
                default -> { }
            }
            if (problem != null) {
                issues.add(RowIssue.error(col.key(), code, problem));
                error = true;
            }
        }
        return new ImportRow(row.sheet(), row.rowNumber(), values, error ? RowStatus.INVALID : row.status(), issues,
                row.extra());
    }

    static LocalDate parseDate(String v) {
        try {
            return LocalDate.parse(v, ISO);
        } catch (RuntimeException ignored) {
            // thử dạng dd/MM/yyyy
        }
        try {
            return LocalDate.parse(v, VN);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static Boolean parseBoolean(String v) {
        return switch (v.trim().toLowerCase(Locale.ROOT)) {
            case "true", "có", "co", "x", "yes", "1" -> Boolean.TRUE;
            case "false", "không", "khong", "no", "0" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static String matchEnum(ColumnSpec col, String v) {
        for (String allowed : col.enumValues()) {
            if (allowed.equalsIgnoreCase(v.trim())) return allowed;
        }
        return null;
    }

    private static String normalizeList(String v) {
        List<String> parts = new ArrayList<>();
        for (String p : v.split(";")) {
            String t = p.trim();
            if (!t.isEmpty()) parts.add(t);
        }
        return String.join(";", parts);
    }
}
