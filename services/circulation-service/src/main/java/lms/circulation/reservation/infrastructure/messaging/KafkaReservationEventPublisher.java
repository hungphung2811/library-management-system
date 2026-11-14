package lms.circulation.reservation.infrastructure.messaging;

import lms.circulation.reservation.application.PublishReservationEventPort;
import lms.common.kernel.DomainEvent;
import lms.messaging.KafkaEventPublisher;

public class KafkaReservationEventPublisher implements PublishReservationEventPort {

    private final KafkaEventPublisher delegate;

    public KafkaReservationEventPublisher(KafkaEventPublisher delegate) {
        this.delegate = delegate;
    }

    @Override
    public void publish(DomainEvent event) {
        delegate.publish(event);
    }
}
