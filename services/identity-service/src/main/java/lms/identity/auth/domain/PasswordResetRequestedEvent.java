package lms.identity.auth.domain;

import lms.common.kernel.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Có yêu cầu quên mật khẩu hợp lệ. Mang TOKEN THÔ để kênh gửi thư (chưa có) tạo liên kết: vì vậy chỉ phát lên topic
 * riêng, KHÔNG phát lên auth.events (audit lưu thân event). toString che token để không lộ qua log.
 */
public record PasswordResetRequestedEvent(UUID eventId, UUID userId, String username, String token, Instant expiresAt,
                                          Instant occurredAt) implements DomainEvent {

    @Override
    public String aggregateId() {
        return userId.toString();
    }

    @Override
    public String toString() {
        return "PasswordResetRequestedEvent[eventId=" + eventId + ", userId=" + userId + ", token=***]";
    }
}
