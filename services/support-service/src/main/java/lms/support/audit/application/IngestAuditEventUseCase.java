package lms.support.audit.application;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Điều phối xử lý một event: ghi audit vào PostgreSQL TRƯỚC (có transaction riêng, đã commit khi trả về),
 * rồi lưu bản thô vào MongoDB theo kiểu best-effort. Hai bước nằm ở hai database khác nhau nên KHÔNG có
 * atomicity xuyên DB: lỗi Mongo chỉ được log, không làm mất audit log và không rollback Postgres. Event
 * giao lại sẽ lưu bù bản thô vì archive idempotent theo eventId.
 *
 * Lớp này cố ý không có @Transactional để Postgres commit xong rồi mới đụng tới Mongo.
 */
public class IngestAuditEventUseCase {

    private static final Logger log = LoggerFactory.getLogger(IngestAuditEventUseCase.class);

    private final RecordAuditEventUseCase recordUseCase;
    private final ArchiveRawEventUseCase archiveUseCase;
    private final Clock clock;

    public IngestAuditEventUseCase(RecordAuditEventUseCase recordUseCase, ArchiveRawEventUseCase archiveUseCase,
                                   Clock clock) {
        this.recordUseCase = recordUseCase;
        this.archiveUseCase = archiveUseCase;
        this.clock = clock;
    }

    /** @return true nếu audit log mới được tạo; false nếu event đã được ghi trước đó. */
    public boolean execute(IngestAuditEventCommand command) {
        boolean recorded = recordUseCase.execute(new RecordAuditEventCommand(command.eventId(),
                command.eventType(), command.aggregateId(), command.occurredAt(), command.payload()));
        try {
            archiveUseCase.execute(new ArchiveRawEventCommand(command.eventId(), command.topic(),
                    command.eventType(), command.aggregateId(), clock.instant(), command.rawEnvelope()));
        } catch (RuntimeException e) {
            log.warn("Không lưu được bản thô của event {} vào LOG_DB (best-effort, audit log đã {}): {}",
                    command.eventId(), recorded ? "được ghi" : "tồn tại từ trước", e.getMessage());
        }
        return recorded;
    }
}
