package lms.identity.user.domain;

/**
 * Phiên không có, không thuộc người dùng đích, ở tenant khác, là phiên nền tảng hoặc đã kết thúc: một mã chung
 * ({@code SESSION_NOT_FOUND}, HTTP 404), không lộ sự tồn tại của phiên.
 */
public class UserSessionNotFoundException extends RuntimeException {
    public UserSessionNotFoundException() {
        super("Không tìm thấy phiên đăng nhập");
    }
}
