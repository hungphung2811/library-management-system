package lms.support.audit.infrastructure.persistence;

import lms.database.api.MongoConnection;
import lms.database.core.DatabaseConnectionRegistry;
import lms.support.audit.application.ArchiveRawEventCommand;
import lms.support.audit.application.RawEventArchivePort;
import java.util.Optional;
import java.util.UUID;
import lms.tenant.TenantContext;
import org.bson.Document;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Adapter MongoDB cho LOG_DB. Lấy MongoTemplate từ registry ở MỖI lần gọi: LOG_DB là connection phụ
 * (critical=false), có thể tắt hoặc lỗi lúc khởi động mà service vẫn chạy; khi đó archive báo lỗi và
 * use case gọi nó chỉ log (best-effort).
 */
public class MongoRawEventArchive implements RawEventArchivePort {

    static final String COLLECTION = "raw_events";

    private final DatabaseConnectionRegistry registry;
    private final String connectionName;

    public MongoRawEventArchive(DatabaseConnectionRegistry registry, String connectionName) {
        this.registry = registry;
        this.connectionName = connectionName;
    }

    @Override
    public void archive(ArchiveRawEventCommand command) {
        UUID tenantId = TenantContext.require();
        // _id gồm tenant để event cùng id của hai tenant không ghi đè nhau.
        Document document = new Document("_id", documentId(tenantId, command.eventId()))
                .append("tenantId", tenantId.toString())
                .append("eventId", command.eventId().toString())
                .append("topic", command.topic())
                .append("eventType", command.eventType())
                .append("aggregateId", command.aggregateId())
                .append("receivedAt", java.util.Date.from(command.receivedAt()))
                .append("raw", command.rawEnvelope());
        // save() theo _id: ghi lại cùng (tenant, eventId) thì thay thế, không nhân bản.
        registry.require(connectionName, MongoConnection.class).mongoTemplate().save(document, COLLECTION);
    }

    /** Envelope thô của event trong tenant hiện tại; event của tenant khác (hoặc không có tenant) không được thấy. */
    public Optional<String> findRawEnvelope(UUID eventId) {
        UUID tenantId = TenantContext.current().orElse(null);
        if (tenantId == null) {
            return Optional.empty();
        }
        Query query = Query.query(Criteria.where("tenantId").is(tenantId.toString()).and("eventId").is(eventId.toString()));
        Document document = registry.require(connectionName, MongoConnection.class).mongoTemplate()
                .findOne(query, Document.class, COLLECTION);
        return Optional.ofNullable(document).map(d -> d.getString("raw"));
    }

    static String documentId(UUID tenantId, UUID eventId) {
        return tenantId + ":" + eventId;
    }
}
