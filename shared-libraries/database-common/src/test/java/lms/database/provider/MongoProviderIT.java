package lms.database.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import lms.database.api.ConnectionSpec;
import lms.database.api.DatabaseType;
import lms.database.api.MongoConnection;
import lms.database.provider.mongo.MongoProvider;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("docker")
@Testcontainers
class MongoProviderIT {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

    private final MongoProvider provider = new MongoProvider();

    private ConnectionSpec spec() {
        ConnectionSpec spec = new ConnectionSpec();
        spec.setType(DatabaseType.MONGODB);
        spec.setUri(mongo.getReplicaSetUrl("logdb"));
        return spec;
    }

    @Test
    void healthUpVaGhiDoc() {
        try (MongoConnection connection = provider.create("LOG_DB", spec())) {
            assertThat(connection.health().isUp()).isTrue();

            connection.mongoTemplate().getCollection("logs").insertOne(new Document("_id", "1").append("msg", "xin chào"));

            assertThat(connection.mongoTemplate().getCollection("logs").countDocuments()).isEqualTo(1);
            assertThat(connection.mongoTemplate().getCollection("logs").find().first()).containsEntry("msg", "xin chào");
        }
    }

    @Test
    void transactionCommitVaRollbackDungCach() {
        try (MongoConnection connection = provider.create("TX_DB", spec())) {
            var template = connection.mongoTemplate();
            template.getCollection("tx_logs").drop();
            template.createCollection("tx_logs"); // collection phải có sẵn trước khi ghi trong transaction
            TransactionTemplate tx = new TransactionTemplate(connection.transactionManager());

            tx.executeWithoutResult(s -> template.insert(new Document("_id", "ok"), "tx_logs"));
            assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
                template.insert(new Document("_id", "rollback"), "tx_logs");
                throw new IllegalStateException("lỗi nghiệp vụ");
            })).hasMessage("lỗi nghiệp vụ");

            assertThat(template.getCollection("tx_logs").countDocuments()).isEqualTo(1);
            assertThat(template.getCollection("tx_logs").find(new Document("_id", "rollback")).first()).isNull();
        }
    }

    @Test
    void thieuTenDatabaseThiBaoLoiRo() {
        ConnectionSpec spec = spec();
        spec.setUri("mongodb://" + mongo.getHost() + ":" + mongo.getFirstMappedPort() + "/?replicaSet=rs0");

        assertThatThrownBy(() -> provider.create("NO_DB", spec)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tên database");
    }

    @Test
    void closeLaIdempotentVaHealthSauKhiDongLaDown() {
        MongoConnection connection = provider.create("CLOSE_DB", spec());

        connection.close();
        connection.close();

        assertThat(connection.health().isUp()).isFalse();
    }

    @Test
    void khongKetNoiDuocThiNemLoiSauServerSelectionTimeout() {
        ConnectionSpec dead = new ConnectionSpec();
        dead.setType(DatabaseType.MONGODB);
        dead.setUri("mongodb://localhost:1/deaddb");
        dead.getTimeout().setConnect(java.time.Duration.ofMillis(800));

        assertThatThrownBy(() -> provider.create("DEAD_DB", dead)).isInstanceOf(RuntimeException.class);
    }
}
