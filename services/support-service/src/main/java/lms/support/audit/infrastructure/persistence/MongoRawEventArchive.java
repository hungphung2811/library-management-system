package lms.support.audit.infrastructure.persistence;

import lms.database.api.MongoConnection;
import lms.database.core.DatabaseConnectionRegistry;
import lms.support.audit.application.ArchiveRawEventCommand;
import lms.support.audit.application.RawEventArchivePort;
import org.bson.Document;

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
        Document document = new Document("_id", command.eventId().toString())
                .append("topic", command.topic())
                .append("eventType", command.eventType())
                .append("aggregateId", command.aggregateId())
                .append("receivedAt", java.util.Date.from(command.receivedAt()))
                .append("raw", command.rawEnvelope());
        // save() theo _id: ghi lại cùng eventId thì thay thế, không nhân bản.
        registry.require(connectionName, MongoConnection.class).mongoTemplate().save(document, COLLECTION);
    }
}
