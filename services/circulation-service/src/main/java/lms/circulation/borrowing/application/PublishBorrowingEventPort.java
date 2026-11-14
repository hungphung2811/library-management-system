package lms.circulation.borrowing.application;

import lms.common.kernel.DomainEvent;

public interface PublishBorrowingEventPort {
    void publish(DomainEvent event);
}
