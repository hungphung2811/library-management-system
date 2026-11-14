package lms.identity.auth.domain;

/** Dùng chung một thông điệp cho "sai username" và "sai password" để không lộ tài khoản nào tồn tại. */
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() {
        super("Tên đăng nhập hoặc mật khẩu không đúng");
    }
}
