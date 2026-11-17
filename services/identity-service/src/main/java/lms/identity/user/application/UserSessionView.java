package lms.identity.user.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Phiên của một người dùng trong tenant hiện tại, cho quản trị viên. Không có tenantId (luôn là tenant hiện tại) và
 * không có cờ current. ipAddress là IP đầy đủ (người gọi đã qua quy tắc G1-G6, việc xem được ghi audit).
 */
public record UserSessionView(UUID sessionId, String channel, String deviceSummary, String ipAddress,
                              Instant startedAt, Instant lastUsedAt, Instant expiresAt) {
}
