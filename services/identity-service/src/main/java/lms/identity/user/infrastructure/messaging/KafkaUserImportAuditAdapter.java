package lms.identity.user.infrastructure.messaging;

import lms.identity.user.application.UserImportAuditPort;
import lms.identity.user.domain.UsersImportedEvent;
import lms.messaging.KafkaEventPublisher;

/** Phát bản ghi audit tóm tắt của lần nhập người dùng lên topic auth.events (best-effort, ADR-0001). */
public class KafkaUserImportAuditAdapter implements UserImportAuditPort {

    private final KafkaEventPublisher publisher;

    public KafkaUserImportAuditAdapter(KafkaEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void record(UsersImportedEvent event) {
        publisher.publish(event);
    }
}
