package lms.identity.auth.application;

import java.time.Instant;
import java.util.UUID;

/** Phiên của một người dùng ở MỘT tenant (cho quản trị viên tenant). Không có tenantId (luôn là tenant được hỏi). */
public record TenantUserSession(UUID sessionId, String channel, String deviceSummary, String ipAddress,
                                Instant startedAt, Instant lastUsedAt, Instant expiresAt) {
}
