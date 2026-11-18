package lms.identity.user.domain;

import java.time.LocalDate;
import java.util.regex.Pattern;

/** Quy tắc chung cho các trường thông tin cá nhân tùy chọn (chuẩn hóa: cắt khoảng trắng, chuỗi rỗng thành null). */
final class PersonalRules {

    static final int MAX_AGE_YEARS = 120;
    private static final Pattern PHONE = Pattern.compile("^[0-9+ \\-]{1,20}$");
    private static final Pattern HTTP_URL = Pattern.compile("^https?://\\S+$", Pattern.CASE_INSENSITIVE);

    private PersonalRules() {
    }

    static String text(String value, int max, String label) {
        if (value == null) return null;
        String v = value.trim();
        if (v.isEmpty()) return null;
        if (v.length() > max) throw new IllegalArgumentException(label + " tối đa " + max + " ký tự");
        return v;
    }

    static String phone(String value) {
        String v = text(value, 20, "Số điện thoại");
        if (v != null && !PHONE.matcher(v).matches()) {
            throw new IllegalArgumentException("Số điện thoại chỉ gồm chữ số, '+', '-' và khoảng trắng, tối đa 20 ký tự");
        }
        return v;
    }

    static String httpUrl(String value, int max, String label) {
        String v = text(value, max, label);
        if (v != null && !HTTP_URL.matcher(v).matches()) {
            throw new IllegalArgumentException(label + " phải là địa chỉ http hoặc https");
        }
        return v;
    }

    static LocalDate dateOfBirth(LocalDate value, LocalDate today) {
        if (value == null) return null;
        if (value.isAfter(today)) throw new IllegalArgumentException("Ngày sinh không được ở tương lai");
        if (value.isBefore(today.minusYears(MAX_AGE_YEARS))) {
            throw new IllegalArgumentException("Tuổi không được quá " + MAX_AGE_YEARS);
        }
        return value;
    }
}
