package lms.identity.tenant.domain;

/** Tắt module cuối cùng đang bật: tenant luôn phải có ít nhất một module (muốn ngưng thì suspend tenant). */
public class TenantModuleRequiredException extends RuntimeException {
    public TenantModuleRequiredException(String message) {
        super(message);
    }
}
