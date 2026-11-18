package lms.catalog.catalog.application.imports;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lms.imports.core.ImportRow;

/** Đọc giá trị ô (đã được thư viện chuẩn hóa thành chuỗi) sang kiểu Java; lỗi thành {@link RowProblem}. */
final class ImportValues {

    private ImportValues() {
    }

    /** Chuỗi đã cắt khoảng trắng, hoặc null nếu trống. */
    static String text(ImportRow row, String key) {
        String v = row.value(key).strip();
        return v.isEmpty() ? null : v;
    }

    static Integer integer(ImportRow row, String key) {
        String v = text(row, key);
        if (v == null) return null;
        try {
            return new java.math.BigInteger(v).intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new RowProblem(key, "INVALID_NUMBER", "Số nguyên quá lớn hoặc không hợp lệ");
        }
    }

    static Long longValue(ImportRow row, String key) {
        String v = text(row, key);
        if (v == null) return null;
        try {
            return new java.math.BigInteger(v).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new RowProblem(key, "INVALID_NUMBER", "Số nguyên quá lớn hoặc không hợp lệ");
        }
    }

    static BigDecimal decimal(ImportRow row, String key) {
        String v = text(row, key);
        if (v == null) return null;
        try {
            return new BigDecimal(v);
        } catch (NumberFormatException e) {
            throw new RowProblem(key, "INVALID_NUMBER", "Phải là số");
        }
    }

    /** Ngày đã được thư viện đưa về yyyy-MM-dd. */
    static LocalDate date(ImportRow row, String key) {
        String v = text(row, key);
        if (v == null) return null;
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            throw new RowProblem(key, "INVALID_DATE", "Ngày phải có dạng dd/MM/yyyy hoặc yyyy-MM-dd");
        }
    }

    /** Danh sách tách bằng dấu chấm phẩy (thư viện đã bỏ phần tử rỗng). */
    static List<String> list(ImportRow row, String key) {
        String v = text(row, key);
        if (v == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String p : Arrays.asList(v.split(";"))) {
            String t = p.strip();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
