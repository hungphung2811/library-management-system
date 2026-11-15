package lms.identity.user.domain;

public class DuplicateUserException extends RuntimeException {

    /**
     * Thông điệp chung cho mọi trường hợp trùng (tên đăng nhập, email, id) ở bất kỳ tenant nào: không tiết lộ tài khoản
     * đó thuộc tenant nào hay có tồn tại ở tenant khác không (Review Focus 4).
     */
    public static final String GENERIC_MESSAGE = "Tên đăng nhập hoặc email đã tồn tại";

    public DuplicateUserException(String message) {
        super(message);
    }

    public static DuplicateUserException generic() {
        return new DuplicateUserException(GENERIC_MESSAGE);
    }
}
