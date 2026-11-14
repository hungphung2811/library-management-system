package lms.database.provider.mongo;

import lms.database.api.ConnectionSpec;
import lms.database.api.DatabaseProvider;
import lms.database.api.DatabaseType;
import lms.database.api.MongoConnection;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;

/** MongoDB qua Spring Data MongoDB; pool do chính driver quản lý. */
public class MongoProvider implements DatabaseProvider<MongoConnection> {

    @Override
    public DatabaseType type() {
        return DatabaseType.MONGODB;
    }

    @Override
    public MongoConnection create(String name, ConnectionSpec spec) {
        ConnectionString connectionString = new ConnectionString(spec.getUri());
        String database = spec.getDatabase() != null && !spec.getDatabase().isBlank()
                ? spec.getDatabase() : connectionString.getDatabase();
        if (database == null) {
            throw new IllegalArgumentException("Connection " + name + " cần tên database trong uri hoặc thuộc tính database");
        }
        long connectMs = spec.getTimeout().getConnect().toMillis();
        var pool = spec.getPool();
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(connectionString)
                .applyToClusterSettings(b -> b.serverSelectionTimeout(connectMs, TimeUnit.MILLISECONDS))
                .applyToSocketSettings(b -> b.connectTimeout((int) connectMs, TimeUnit.MILLISECONDS))
                .applyToConnectionPoolSettings(b -> b.maxSize(pool.getMaxSize()).minSize(pool.getMinIdle())
                        .maxConnectionIdleTime(pool.getIdleTimeout().toMillis(), TimeUnit.MILLISECONDS)
                        .maxConnectionLifeTime(pool.getMaxLifetime().toMillis(), TimeUnit.MILLISECONDS))
                .build();

        MongoClient client = MongoClients.create(settings);
        try {
            SimpleMongoClientDatabaseFactory factory = new SimpleMongoClientDatabaseFactory(client, database);
            MongoTemplate template = new MongoTemplate(factory);
            // Kiểm tra kết nối thật ngay lúc tạo để manager còn retry được.
            template.getDb().runCommand(new Document("ping", 1));
            return new DefaultMongoConnection(name, client, template, new MongoTransactionManager(factory));
        } catch (RuntimeException e) {
            client.close();
            throw e;
        }
    }
}
