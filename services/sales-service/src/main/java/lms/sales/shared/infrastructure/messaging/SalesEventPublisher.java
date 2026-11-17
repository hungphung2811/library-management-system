package lms.sales.shared.infrastructure.messaging;

import lms.messaging.KafkaEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Phát sales.events SAU khi transaction commit (nếu có transaction), best-effort: lỗi chỉ log, không làm hỏng request
 * và không ảnh hưởng dữ liệu đã commit. Rollback thì không phát gì. {@code delegate} null = tắt phát (test, cấu hình).
 */
public class SalesEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(SalesEventPublisher.class);

    private final KafkaEventPublisher delegate;

    public SalesEventPublisher(KafkaEventPublisher delegate) {
        this.delegate = delegate;
    }

    public void publishAfterCommit(SalesEvent event) {
        if (delegate == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send(event);
                }
            });
        } else {
            send(event);
        }
    }

    private void send(SalesEvent event) {
        try {
            delegate.publish(event);
        } catch (RuntimeException e) {
            log.error("Không phát được {} lên sales.events, eventId={}", event.eventType(), event.eventId(), e);
        }
    }
}
