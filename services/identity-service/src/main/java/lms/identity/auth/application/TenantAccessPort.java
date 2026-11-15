package lms.identity.auth.application;

import java.util.List;
import java.util.UUID;
import lms.identity.tenant.application.TenantChoice;
import lms.identity.tenant.application.TenantSummary;

/**
 * Chọn và kiểm tra tenant cho phiên đăng nhập, do context tenant đảm nhiệm (adapter gọi tầng application của tenant).
 * Các phương thức từ chối bằng NoTenantAccessException của context tenant (HTTP 403 NO_TENANT_ACCESS).
 */
public interface TenantAccessPort {

    /** Chọn tenant lúc đăng nhập; platformAdmin: không còn tenant nào thì trả lựa chọn không có tenant. */
    TenantChoice selectForLogin(UUID userId, String tenantCodeOrNull, boolean platformAdmin);

    /** Đổi sang tenant khác (kiểm tra quyền, ghi tenant dùng gần nhất). */
    TenantChoice switchTo(UUID userId, UUID tenantId);

    /** Kiểm tra lại quyền ở tenant, không ghi gì (dùng khi làm mới phiên). */
    TenantChoice verify(UUID userId, UUID tenantId);

    /** Các tenant truy cập được. */
    List<TenantSummary> available(UUID userId);

    /** Thêm người dùng vào tenant (idempotent). */
    void addMember(UUID userId, UUID tenantId);
}
