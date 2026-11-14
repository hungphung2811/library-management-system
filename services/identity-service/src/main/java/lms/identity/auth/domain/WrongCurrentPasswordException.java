package lms.identity.auth.domain;

public class WrongCurrentPasswordException extends RuntimeException {
    public WrongCurrentPasswordException() {
        super("Mật khẩu hiện tại không đúng");
    }
}
