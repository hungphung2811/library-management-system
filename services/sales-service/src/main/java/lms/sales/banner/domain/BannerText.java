package lms.sales.banner.domain;

import java.text.Normalizer;
import java.util.Map;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;

/**
 * Chuẩn hóa văn bản thuần của banner (ADR-0019 2.5): NFC, cắt khoảng trắng đầu/cuối, từ chối mọi ký tự điều khiển (kể
 * cả xuống dòng), độ dài tính theo code point sau chuẩn hóa. Lỗi chỉ nêu tên trường, không chứa giá trị nhập.
 */
final class BannerText {

    private BannerText() {
    }

    static SalesException invalid(String field) {
        return new SalesException(ErrorCode.INVALID_BANNER, "Banner không hợp lệ", Map.of("field", field));
    }

    /** Trả về chuỗi đã chuẩn hóa, hoặc null khi đầu vào null. */
    static String normalize(String raw, String field) {
        if (raw == null) {
            return null;
        }
        String s = Normalizer.normalize(raw, Normalizer.Form.NFC).strip();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (Character.getType(cp) == Character.CONTROL) {
                throw invalid(field);
            }
            i += Character.charCount(cp);
        }
        return s;
    }

    static String required(String raw, int maxCodePoints, String field) {
        String s = normalize(raw, field);
        if (s == null || s.isEmpty() || s.codePointCount(0, s.length()) > maxCodePoints) {
            throw invalid(field);
        }
        return s;
    }

    /** Tùy chọn: rỗng/null thành null. */
    static String optional(String raw, int maxCodePoints, String field) {
        String s = normalize(raw, field);
        if (s == null || s.isEmpty()) {
            return null;
        }
        if (s.codePointCount(0, s.length()) > maxCodePoints) {
            throw invalid(field);
        }
        return s;
    }
}
