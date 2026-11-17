package lms.identity.auth.infrastructure.messaging;

import lms.identity.auth.application.PublishPasswordResetPort;
import lms.identity.auth.domain.PasswordResetRequestedEvent;
import lms.messaging.KafkaEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phát PasswordResetRequested (mang token thô) lên topic RIÊNG (không phải auth.events, vì audit lưu thân event).
 * Chưa có dịch vụ gửi thư: cờ {@code lms.identity.password-reset.log-token} (mặc định tắt; chỉ bật ở profile dev) ghi
 * token ra log mức INFO để thử luồng. Production KHÔNG được bật cờ này.
 */
public class PasswordResetEventPublisher implements PublishPasswordResetPort {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetEventPublisher.class);

    private final KafkaEventPublisher delegate;
    private final boolean logToken;

    public PasswordResetEventPublisher(KafkaEventPublisher delegate, boolean logToken) {
        this.delegate = delegate;
        this.logToken = logToken;
    }

    @Override
    public void publish(PasswordResetRequestedEvent event) {
        if (logToken) {
            log.info("[DEV] Token đặt lại mật khẩu của '{}' (hết hạn {}): {}", event.username(), event.expiresAt(),
                    event.token());
        }
        delegate.publish(event);
    }
}
