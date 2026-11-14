package lms.support.audit.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import lms.database.core.DatabaseConnectionRegistry;
import lms.support.audit.application.ArchiveRawEventUseCase;
import lms.support.audit.application.IngestAuditEventUseCase;
import lms.support.audit.application.QueryAuditLogsUseCase;
import lms.support.audit.application.RecordAuditEventUseCase;
import lms.support.audit.infrastructure.messaging.AuditEventParser;
import lms.support.audit.infrastructure.persistence.AuditLogPersistenceAdapter;
import lms.support.audit.infrastructure.persistence.MongoRawEventArchive;
import lms.support.audit.infrastructure.persistence.SpringDataAuditLogRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/** Composition root của ngữ cảnh audit: nối domain/application (không có annotation Spring) với adapter. */
@Configuration
public class AuditBeanConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Phụ thuộc auditFlyway để bảng audit_logs đã tồn tại trước khi dùng. */
    @Bean
    @DependsOn("auditFlyway")
    AuditLogPersistenceAdapter auditLogPersistenceAdapter(SpringDataAuditLogRepository jpa) {
        return new AuditLogPersistenceAdapter(jpa);
    }

    @Bean
    MongoRawEventArchive mongoRawEventArchive(DatabaseConnectionRegistry registry) {
        return new MongoRawEventArchive(registry, "LOG_DB");
    }

    @Bean
    RecordAuditEventUseCase recordAuditEventUseCase(AuditLogPersistenceAdapter persistence, Clock clock) {
        return new RecordAuditEventUseCase(persistence, clock);
    }

    @Bean
    QueryAuditLogsUseCase queryAuditLogsUseCase(AuditLogPersistenceAdapter persistence) {
        return new QueryAuditLogsUseCase(persistence);
    }

    @Bean
    ArchiveRawEventUseCase archiveRawEventUseCase(MongoRawEventArchive archive) {
        return new ArchiveRawEventUseCase(archive);
    }

    @Bean
    IngestAuditEventUseCase ingestAuditEventUseCase(RecordAuditEventUseCase record, ArchiveRawEventUseCase archive,
                                                    Clock clock) {
        return new IngestAuditEventUseCase(record, archive, clock);
    }

    @Bean
    AuditEventParser auditEventParser(ObjectMapper objectMapper) {
        return new AuditEventParser(objectMapper);
    }
}
