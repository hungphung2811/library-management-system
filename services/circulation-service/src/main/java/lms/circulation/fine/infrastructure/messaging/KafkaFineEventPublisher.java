package lms.circulation.fine.infrastructure.messaging;

import lms.circulation.fine.application.PublishFineEventPort;
import lms.common.kernel.DomainEvent;
import lms.messaging.KafkaEventPublisher;

public class KafkaFineEventPublisher implements PublishFineEventPort {

    private final KafkaEventPublisher delegate;

    public KafkaFineEventPublisher(KafkaEventPublisher delegate) {
        this.delegate = delegate;
    }

    @Override
    public void publish(DomainEvent event) {
        delegate.publish(event);
    }
}
