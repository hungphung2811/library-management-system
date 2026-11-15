package lms.identity.auth.domain;

public class DuplicateUsernameException extends RuntimeException {
    public DuplicateUsernameException(String username) {
        super("Tên đăng nhập đã tồn tại: " + username);
    }

    private DuplicateUsernameException() {
        super("Tên đăng nhập hoặc tài khoản đã tồn tại");
    }

    /** Thông điệp chung, không nêu tên đăng nhập hay lý do cụ thể (ví dụ tài khoản thuộc tenant khác). */
    public static DuplicateUsernameException generic() {
        return new DuplicateUsernameException();
    }
}
