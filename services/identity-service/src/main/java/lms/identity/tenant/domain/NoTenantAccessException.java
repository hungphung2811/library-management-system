package lms.identity.tenant.domain;

/**
 * Không truy cập được tenant: mã tenant không tồn tại, không có membership ACTIVE, tenant bị tạm ngưng, hoặc người
 * dùng không còn tenant nào. Mọi trường hợp dùng CÙNG một thông điệp để không lộ tenant nào tồn tại hay lý do.
 */
public class NoTenantAccessException extends RuntimeException {

    public static final String MESSAGE = "Không có quyền truy cập thư viện";

    public NoTenantAccessException() {
        super(MESSAGE);
    }
}
