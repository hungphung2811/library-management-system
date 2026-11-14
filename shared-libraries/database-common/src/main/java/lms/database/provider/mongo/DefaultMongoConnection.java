package lms.database.provider.mongo;

import lms.database.api.DatabaseType;
import lms.database.api.HealthStatus;
import lms.database.api.MongoConnection;
import com.mongodb.client.MongoClient;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bson.Document;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;

class DefaultMongoConnection implements MongoConnection {

    private final String name;
    private final MongoClient client;
    private final MongoTemplate template;
    private final MongoTransactionManager transactionManager;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    DefaultMongoConnection(String name, MongoClient client, MongoTemplate template,
                           MongoTransactionManager transactionManager) {
        this.name = name;
        this.client = client;
        this.template = template;
        this.transactionManager = transactionManager;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public DatabaseType type() {
        return DatabaseType.MONGODB;
    }

    @Override
    public MongoTemplate mongoTemplate() {
        return template;
    }

    @Override
    public MongoTransactionManager transactionManager() {
        return transactionManager;
    }

    @Override
    public HealthStatus health() {
        if (closed.get()) {
            return HealthStatus.down("Connection đã đóng");
        }
        try {
            template.getDb().runCommand(new Document("ping", 1));
            return HealthStatus.up("MONGODB");
        } catch (RuntimeException e) {
            return HealthStatus.down(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            client.close();
        }
    }
}
