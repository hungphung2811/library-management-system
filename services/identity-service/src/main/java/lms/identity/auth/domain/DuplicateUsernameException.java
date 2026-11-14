package lms.identity.auth.domain;

public class DuplicateUsernameException extends RuntimeException {
    public DuplicateUsernameException(String username) {
        super("Tên đăng nhập đã tồn tại: " + username);
    }
}
