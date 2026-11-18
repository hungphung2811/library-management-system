package lms.identity.auth.domain;

/** Tài khoản khách chưa xác minh email nên chưa được đăng nhập storefront (chỉ ném SAU khi mật khẩu đã đúng). */
public class EmailNotVerifiedException extends RuntimeException {

    public static final String MESSAGE = "Email chưa được xác minh";

    public EmailNotVerifiedException() {
        super(MESSAGE);
    }
}
