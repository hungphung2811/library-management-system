package lms.system.cms.domain;

/**
 * Quy tắc ký tự cho chuỗi do người dùng nhập. NUL làm PostgreSQL từ chối ghi (500), các ký tự điều khiển khác không
 * có ý nghĩa hiển thị; chặn sớm ở domain để trả 400 thay vì lỗi lưu trữ.
 */
final class TextRules {

    private TextRules() {
    }

    /** Có ký tự điều khiển (NUL, ESC, DEL, C1...). {@code allowWhitespaceControls} cho phép \n, \r, \t. */
    static boolean hasControlChars(String value, boolean allowWhitespaceControls) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c)
                    && !(allowWhitespaceControls && (c == '\n' || c == '\r' || c == '\t'))) {
                return true;
            }
        }
        return false;
    }
}
