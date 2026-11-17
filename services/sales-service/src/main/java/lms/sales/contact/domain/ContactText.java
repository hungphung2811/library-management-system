package lms.sales.contact.domain;

import java.text.Normalizer;
import lms.sales.shared.domain.SalesException;

/**
 * Chuẩn hóa và kiểm văn bản người dùng nhập (R3): NFC, "\r\n" và "\r" thành "\n", cắt khoảng trắng đầu/cuối. Ký tự
 * điều khiển (trừ "\n" và "\t" ở nội dung nhiều dòng) bị từ chối (AC4). Độ dài tính theo code point SAU chuẩn hóa.
 * Thông điệp lỗi không chứa giá trị người dùng nhập (R10).
 */
final class ContactText {

    private ContactText() {
    }

    static String normalize(String raw, boolean multiline, String field) {
        if (raw == null) {
            return null;
        }
        String s = Normalizer.normalize(raw, Normalizer.Form.NFC);
        if (multiline) {
            s = s.replace("\r\n", "\n").replace('\r', '\n');
        }
        s = s.strip();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            boolean allowed = multiline && (cp == '\n' || cp == '\t');
            if (!allowed && Character.getType(cp) == Character.CONTROL) {
                throw SalesException.invalidRequest(field + ": chứa ký tự điều khiển không hợp lệ");
            }
            i += Character.charCount(cp);
        }
        return s;
    }

    static String required(String raw, boolean multiline, int maxCodePoints, String field) {
        String s = normalize(raw, multiline, field);
        if (s == null || s.isEmpty()) {
            throw SalesException.invalidRequest(field + ": không được để trống");
        }
        checkLength(s, maxCodePoints, field);
        return s;
    }

    static void checkLength(String s, int maxCodePoints, String field) {
        if (s.codePointCount(0, s.length()) > maxCodePoints) {
            throw SalesException.invalidRequest(field + ": vượt quá " + maxCodePoints + " ký tự");
        }
    }
}
