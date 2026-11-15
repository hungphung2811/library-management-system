package lms.identity.tenant.infrastructure.messaging;

import lms.common.kernel.DomainEvent;
import lms.identity.tenant.application.PublishPlatformEventPort;
import lms.messaging.KafkaEventPublisher;

/** Phát event nền tảng lên topic identity-events (best-effort, envelope lấy tenantId từ TenantContext). */
public class KafkaPlatformEventPublisher implements PublishPlatformEventPort {

    private final KafkaEventPublisher delegate;

    public KafkaPlatformEventPublisher(KafkaEventPublisher delegate) {
        this.delegate = delegate;
    }

    @Override
    public void publish(DomainEvent event) {
        delegate.publish(event);
    }
}
