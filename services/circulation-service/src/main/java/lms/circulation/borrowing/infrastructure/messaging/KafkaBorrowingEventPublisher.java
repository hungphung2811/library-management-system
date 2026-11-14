package lms.circulation.borrowing.infrastructure.messaging;

import lms.circulation.borrowing.application.PublishBorrowingEventPort;
import lms.common.kernel.DomainEvent;
import lms.messaging.KafkaEventPublisher;

public class KafkaBorrowingEventPublisher implements PublishBorrowingEventPort {

    private final KafkaEventPublisher delegate;

    public KafkaBorrowingEventPublisher(KafkaEventPublisher delegate) {
        this.delegate = delegate;
    }

    @Override
    public void publish(DomainEvent event) {
        delegate.publish(event);
    }
}
