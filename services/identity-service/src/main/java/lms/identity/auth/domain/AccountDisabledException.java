package lms.identity.auth.domain;

public class AccountDisabledException extends RuntimeException {
    public AccountDisabledException() {
        super("Tài khoản đã bị vô hiệu hóa");
    }
}
