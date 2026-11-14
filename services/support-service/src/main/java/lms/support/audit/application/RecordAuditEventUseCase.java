package lms.support.audit.application;

import java.time.Clock;
import lms.support.audit.domain.AuditLog;
import org.springframework.transaction.annotation.Transactional;

/** Ghi audit log vào AUDIT_DB (PostgreSQL). Use case này chỉ ghi vào MỘT database. */
public class RecordAuditEventUseCase {

    private final SaveAuditLogPort savePort;
    private final Clock clock;

    public RecordAuditEventUseCase(SaveAuditLogPort savePort, Clock clock) {
        this.savePort = savePort;
        this.clock = clock;
    }

    /** @return true nếu tạo bản ghi mới, false nếu eventId đã được ghi trước đó (idempotent). */
    @Transactional(AuditTransactions.MANAGER)
    public boolean execute(RecordAuditEventCommand command) {
        AuditLog auditLog = AuditLog.capture(command.eventId(), command.eventType(), command.aggregateId(),
                command.occurredAt(), command.payload(), clock.instant());
        return savePort.saveIfAbsent(auditLog);
    }
}
