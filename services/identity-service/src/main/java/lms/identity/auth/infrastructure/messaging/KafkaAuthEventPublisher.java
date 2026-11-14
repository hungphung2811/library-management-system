package lms.identity.auth.infrastructure.messaging;

import lms.identity.auth.application.PublishAuthEventPort;
import lms.common.kernel.DomainEvent;
import lms.messaging.KafkaEventPublisher;

public class KafkaAuthEventPublisher implements PublishAuthEventPort {

    private final KafkaEventPublisher delegate;

    public KafkaAuthEventPublisher(KafkaEventPublisher delegate) {
        this.delegate = delegate;
    }

    @Override
    public void publish(DomainEvent event) {
        delegate.publish(event);
    }
}
