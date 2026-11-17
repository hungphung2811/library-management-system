package lms.identity.auth.domain;

/** Token đặt lại mật khẩu sai, hết hạn hoặc đã dùng: một thông điệp chung, không phân biệt lý do (chống dò). */
public class InvalidPasswordResetTokenException extends RuntimeException {
    public InvalidPasswordResetTokenException() {
        super("Liên kết đặt lại mật khẩu không hợp lệ hoặc đã hết hạn");
    }
}
