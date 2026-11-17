package lms.identity.auth.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Một phiên (thiết bị) đang đăng nhập của chính người dùng. tenantId null = phiên nền tảng. tenantName null nếu không
 * tra được (người dùng không còn là thành viên). lastUsedAt = thời điểm cấp token gần nhất của phiên.
 */
public record SessionView(UUID sessionId, String channel, UUID tenantId, String tenantName, String deviceSummary,
                          String ipAddress, Instant startedAt, Instant lastUsedAt, Instant expiresAt,
                          boolean current) {
}
