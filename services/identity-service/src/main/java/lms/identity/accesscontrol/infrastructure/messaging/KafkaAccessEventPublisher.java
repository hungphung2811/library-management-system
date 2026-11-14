package lms.identity.accesscontrol.infrastructure.messaging;

import lms.identity.accesscontrol.application.PublishAccessEventPort;
import lms.common.kernel.DomainEvent;
import lms.messaging.KafkaEventPublisher;

public class KafkaAccessEventPublisher implements PublishAccessEventPort {

    private final KafkaEventPublisher delegate;

    public KafkaAccessEventPublisher(KafkaEventPublisher delegate) {
        this.delegate = delegate;
    }

    @Override
    public void publish(DomainEvent event) {
        delegate.publish(event);
    }
}
