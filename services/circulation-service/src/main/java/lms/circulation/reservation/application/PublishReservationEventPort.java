package lms.circulation.reservation.application;

import lms.common.kernel.DomainEvent;

public interface PublishReservationEventPort {
    void publish(DomainEvent event);
}
