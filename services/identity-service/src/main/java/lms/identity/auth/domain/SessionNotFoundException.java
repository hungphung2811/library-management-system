package lms.identity.auth.domain;

/** Phiên không có, không thuộc người gọi/phạm vi, hoặc đã kết thúc: một mã chung, không lộ phiên của người khác. */
public class SessionNotFoundException extends RuntimeException {
    public SessionNotFoundException() {
        super("Không tìm thấy phiên đăng nhập");
    }
}
