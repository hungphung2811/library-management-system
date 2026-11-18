package lms.circulation.borrowing.infrastructure.messaging;

import java.time.Duration;
import lms.circulation.borrowing.application.PublishBorrowingEventPort;
import lms.circulation.borrowing.application.PublishLoanReminderEventPort;
import lms.common.kernel.DomainEvent;
import lms.messaging.KafkaEventPublisher;

public class KafkaBorrowingEventPublisher implements PublishBorrowingEventPort, PublishLoanReminderEventPort {

    /** Thời gian chờ broker xác nhận sự kiện nhắc hạn (ADR-0030 D11). */
    static final Duration REMINDER_PUBLISH_TIMEOUT = Duration.ofSeconds(5);

    private final KafkaEventPublisher delegate;

    public KafkaBorrowingEventPublisher(KafkaEventPublisher delegate) {
        this.delegate = delegate;
    }

    @Override
    public void publish(DomainEvent event) {
        delegate.publish(event);
    }

    @Override
    public void publishAndWait(DomainEvent event) {
        delegate.publishAndWait(event, REMINDER_PUBLISH_TIMEOUT);
    }
}
