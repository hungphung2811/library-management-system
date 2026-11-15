package lms.identity.accesscontrol.domain;

/** Xung đột trạng thái khi sửa/xóa role (ví dụ ROLE_IN_USE, LAST_ADMIN_ROLE); trả HTTP 409 với mã lỗi tương ứng. */
public class RoleConflictException extends RuntimeException {

    private final String code;

    public RoleConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
