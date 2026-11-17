package lms.identity.tenant.domain;

/** Tập module yêu cầu không hợp lệ (rỗng khi tạo tenant). */
public class InvalidTenantModulesException extends RuntimeException {
    public InvalidTenantModulesException(String message) {
        super(message);
    }
}
