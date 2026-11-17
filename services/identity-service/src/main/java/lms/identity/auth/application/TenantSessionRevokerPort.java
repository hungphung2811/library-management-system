package lms.identity.auth.application;

import java.util.UUID;

/** Thu hồi phiên (refresh token) của người dùng trong MỘT tenant; phiên nền tảng (không tenant) không bị ảnh hưởng. */
public interface TenantSessionRevokerPort {
    int revokeAllInTenant(UUID userId, UUID tenantId);

    /**
     * Thu hồi MỘT phiên, lọc user_id + tenant_id + session_id trong cùng một câu SQL (refresh_tokens không có RLS nên
     * không được đọc trước rồi ghi sau); 0 = phiên không có, của người khác, ở tenant khác, nền tảng hoặc đã kết thúc.
     */
    default int revokeSessionInTenant(UUID userId, UUID tenantId, UUID sessionId) {
        throw new UnsupportedOperationException("revokeSessionInTenant");
    }
}
