package lms.catalog.inventory.infrastructure.messaging;

import lms.catalog.inventory.application.PublishInventoryEventPort;
import lms.common.kernel.DomainEvent;
import lms.messaging.KafkaEventPublisher;

public class KafkaInventoryEventPublisher implements PublishInventoryEventPort {

    private final KafkaEventPublisher delegate;

    public KafkaInventoryEventPublisher(KafkaEventPublisher delegate) {
        this.delegate = delegate;
    }

    @Override
    public void publish(DomainEvent event) {
        delegate.publish(event);
    }
}
