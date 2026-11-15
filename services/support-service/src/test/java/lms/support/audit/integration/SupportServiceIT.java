package lms.support.audit.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import lms.database.api.ConnectionState;
import lms.database.api.MongoConnection;
import lms.database.api.RelationalConnection;
import lms.database.core.DatabaseConnectionRegistry;
import lms.support.audit.application.ArchiveRawEventCommand;
import lms.support.audit.application.ArchiveRawEventUseCase;
import lms.support.audit.application.AuditTransactions;
import lms.support.audit.application.IngestAuditEventCommand;
import lms.support.audit.application.IngestAuditEventUseCase;
import lms.support.audit.application.QueryAuditLogsUseCase;
import lms.support.audit.application.RawEventArchivePort;
import lms.support.audit.application.RecordAuditEventCommand;
import lms.support.audit.application.RecordAuditEventUseCase;
import lms.support.audit.domain.AuditLogCriteria;
import lms.support.audit.infrastructure.messaging.AuditEventConsumer;
import org.bson.Document;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Khởi động nguyên support-service (context audit) trên PostgreSQL và MongoDB thật, nối qua database-common.
 * Kafka không chạy: broker giả, listener tắt, producer chờ rất ngắn. Cần Docker. Chạy:
 * mvn test -Dtest=SupportServiceIT -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Dapi.version=1.44
 */
@Tag("docker")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.producer.properties.max.block.ms=300",
        "management.endpoint.health.show-details=always",
        "app.security.enabled=false"
})
@Testcontainers
class SupportServiceIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Container
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

    @DynamicPropertySource
    static void databases(DynamicPropertyRegistry registry) {
        registry.add("database.connections.AUDIT_DB.url", postgres::getJdbcUrl);
        registry.add("database.connections.AUDIT_DB.username", postgres::getUsername);
        registry.add("database.connections.AUDIT_DB.password", postgres::getPassword);
        registry.add("database.connections.LOG_DB.uri", () -> mongo.getReplicaSetUrl("audit_logs"));
    }

    /** Use case "xấu" để chứng minh @Transactional của AUDIT_DB không kéo Mongo vào transaction. */
    public static class TransactionProbe {
        private final RecordAuditEventUseCase record;
        private final ArchiveRawEventUseCase archive;

        TransactionProbe(RecordAuditEventUseCase record, ArchiveRawEventUseCase archive) {
            this.record = record;
            this.archive = archive;
        }

        @Transactional(AuditTransactions.MANAGER)
        public void recordArchiveThenFail(UUID eventId, String aggregateId) {
            record.execute(new RecordAuditEventCommand(eventId, "ProbeEvent", aggregateId, Instant.now(), "{}"));
            archive.execute(new ArchiveRawEventCommand(eventId, "probe.events", "ProbeEvent", aggregateId,
                    Instant.now(), "{\"probe\":true}"));
            throw new IllegalStateException("lỗi nghiệp vụ sau khi ghi cả hai DB");
        }
    }

    @TestConfiguration
    static class ProbeConfiguration {
        @Bean
        TransactionProbe transactionProbe(RecordAuditEventUseCase record, ArchiveRawEventUseCase archive) {
            return new TransactionProbe(record, archive);
        }
    }

    /**
     * Ở production migration-service chạy Flyway; trong IT phải tự migrate AUDIT_DB bằng script lấy từ jar
     * migration-service (test classpath) trước khi các test dùng bảng audit_logs.
     */
    @TestConfiguration
    static class AuditFlywayTestConfiguration {
        @Bean(name = "auditFlyway", initMethod = "migrate")
        @DependsOn("databaseLifecycleManager")
        Flyway auditFlyway(DatabaseConnectionRegistry registry) {
            return Flyway.configure()
                    .dataSource(registry.require("AUDIT_DB", RelationalConnection.class).dataSource())
                    .locations("classpath:db/migration/audit")
                    .load();
        }
    }

    @Autowired TestRestTemplate rest;
    @Autowired ApplicationContext context;
    @Autowired DatabaseConnectionRegistry registry;
    @Autowired RecordAuditEventUseCase record;
    @Autowired QueryAuditLogsUseCase query;
    @Autowired IngestAuditEventUseCase ingest;
    @Autowired AuditEventConsumer consumer;
    @Autowired TransactionProbe probe;
    @Autowired @Qualifier("auditDbDataSource") DataSource auditDataSource;

    private int countRows(UUID eventId) {
        return new JdbcTemplate(auditDataSource)
                .queryForObject("SELECT COUNT(*) FROM audit_logs WHERE event_id = ?", Integer.class, eventId);
    }

    private Document rawDocument(UUID eventId) {
        return registry.require("LOG_DB", MongoConnection.class).mongoTemplate()
                .getCollection("raw_events").find(new Document("_id", eventId.toString())).first();
    }

    private RecordAuditEventCommand event(UUID eventId, String type, String aggregateId) {
        return new RecordAuditEventCommand(eventId, type, aggregateId, Instant.now(), "{\"k\":\"v\"}");
    }

    @Test
    void khoiDongMoCaHaiConnectionVaSchemaAuditDbDuocBeanTestMigrate() {
        assertThat(registry.state("AUDIT_DB")).isEqualTo(ConnectionState.UP);
        assertThat(registry.state("LOG_DB")).isEqualTo(ConnectionState.UP);
        // 1 bản ghi thành công do bean auditFlyway của test chạy (script từ jar migration-service)
        assertThat(new JdbcTemplate(auditDataSource)
                .queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success", Integer.class)).isEqualTo(1);
        assertThat(context.getBean("auditDbTransactionManager")).isInstanceOf(JpaTransactionManager.class)
                .isSameAs(registry.require("AUDIT_DB", RelationalConnection.class).transactionManager());
    }

    @Test
    @SuppressWarnings("unchecked")
    void ghiAuditDocLaiQuaUseCaseVaRestCoLocVaPhanTrang() {
        String aggregate = "agg-" + UUID.randomUUID();
        record.execute(event(UUID.randomUUID(), "BookBorrowedEvent", aggregate));
        record.execute(event(UUID.randomUUID(), "BookReturnedEvent", aggregate));
        record.execute(event(UUID.randomUUID(), "BookBorrowedEvent", aggregate));
        record.execute(event(UUID.randomUUID(), "BookBorrowedEvent", "agg-khac-" + UUID.randomUUID()));

        assertThat(query.execute(new AuditLogCriteria(aggregate, null, 0, 20)).total()).isEqualTo(3);

        Map<String, Object> filtered = rest.getForObject(
                "/internal/audit-logs?aggregateId=" + aggregate + "&eventType=BookBorrowedEvent", Map.class);
        assertThat(filtered.get("total")).isEqualTo(2);
        assertThat((List<Map<String, Object>>) filtered.get("items")).extracting(i -> i.get("eventType"))
                .containsOnly("BookBorrowedEvent");

        Map<String, Object> secondPage = rest.getForObject(
                "/internal/audit-logs?aggregateId=" + aggregate + "&page=1&size=2", Map.class);
        assertThat(secondPage.get("total")).isEqualTo(3);
        assertThat((List<?>) secondPage.get("items")).hasSize(1);

        assertThat(rest.getForEntity("/internal/audit-logs?size=1000", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void ghiTrungEventIdKhongTaoBanGhiMoi() {
        UUID eventId = UUID.randomUUID();

        assertThat(record.execute(event(eventId, "BookBorrowedEvent", "agg-dup"))).isTrue();
        assertThat(record.execute(event(eventId, "BookBorrowedEvent", "agg-dup"))).isFalse();

        assertThat(countRows(eventId)).isEqualTo(1);
    }

    @Test
    void ghiTrungDongThoiChiMotLuongTaoBanGhi() throws Exception {
        UUID eventId = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        AtomicInteger created = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            futures.add(pool.submit((Callable<Void>) () -> {
                if (record.execute(event(eventId, "BookBorrowedEvent", "agg-race"))) created.incrementAndGet();
                return null;
            }));
        }
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        assertThat(created.get()).isEqualTo(1);
        assertThat(countRows(eventId)).isEqualTo(1);
    }

    @Test
    void ingestGhiPostgresRoiLuuBanThoVaoMongoVaIdempotent() {
        UUID eventId = UUID.randomUUID();
        IngestAuditEventCommand command = new IngestAuditEventCommand(eventId, "borrowing.events", "BookBorrowedEvent",
                "agg-ingest", Instant.now(), "{\"a\":1}", "{\"raw\":\"nguyên văn\"}");

        assertThat(ingest.execute(command)).isTrue();
        assertThat(ingest.execute(command)).isFalse();

        assertThat(countRows(eventId)).isEqualTo(1);
        Document raw = rawDocument(eventId);
        assertThat(raw).isNotNull();
        assertThat(raw.getString("raw")).isEqualTo("{\"raw\":\"nguyên văn\"}");
        assertThat(raw.getString("topic")).isEqualTo("borrowing.events");
        long copies = registry.require("LOG_DB", MongoConnection.class).mongoTemplate().getCollection("raw_events")
                .countDocuments(new Document("_id", eventId.toString()));
        assertThat(copies).isEqualTo(1);
    }

    @Test
    void consumerXuLyEnvelopeThatVaBoQuaMessageHong() {
        UUID eventId = UUID.randomUUID();
        String message = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"RoleRevokedEvent\",\"aggregateId\":\"user-9\","
                + "\"occurredAt\":\"2026-01-01T00:00:00Z\",\"version\":1,\"payload\":{\"userId\":\"user-9\"}}";

        consumer.onEvent(message, "access-control.events");
        consumer.onEvent("hỏng hoàn toàn", "access-control.events");

        assertThat(countRows(eventId)).isEqualTo(1);
        assertThat(rawDocument(eventId)).isNotNull();
        assertThat(query.execute(new AuditLogCriteria("user-9", "RoleRevokedEvent", 0, 10)).items())
                .singleElement().satisfies(item -> assertThat(item.payload()).contains("user-9"));
    }

    @Test
    void loiMongoKhongLamMatAuditLogTrongPostgres() {
        RawEventArchivePort brokenArchive = command -> {
            throw new IllegalStateException("mongo không dùng được");
        };
        IngestAuditEventUseCase ingestWithBrokenMongo = new IngestAuditEventUseCase(record,
                new ArchiveRawEventUseCase(brokenArchive), java.time.Clock.systemUTC());
        UUID eventId = UUID.randomUUID();

        boolean recorded = ingestWithBrokenMongo.execute(new IngestAuditEventCommand(eventId, "t", "BookBorrowedEvent",
                "agg-mongo-down", Instant.now(), "{}", "{}"));

        assertThat(recorded).isTrue();
        assertThat(countRows(eventId)).isEqualTo(1);
        assertThat(rawDocument(eventId)).isNull();
    }

    @Test
    void rollbackOAuditDbKhongAnhHuongMongoVaTransactionalChonDungManager() {
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> probe.recordArchiveThenFail(eventId, "agg-probe"))
                .hasMessageContaining("lỗi nghiệp vụ");

        // Postgres bị rollback bởi auditDbTransactionManager...
        assertThat(countRows(eventId)).isZero();
        // ...còn Mongo không nằm trong transaction đó nên bản ghi vẫn còn: không có atomicity xuyên DB.
        assertThat(rawDocument(eventId)).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void healthActuatorHienThiCaHaiConnection() {
        Map<String, Object> health = rest.getForObject("/actuator/health", Map.class);

        assertThat(health.get("status")).isEqualTo("UP");
        Map<String, Object> components = (Map<String, Object>) health.get("components");
        Map<String, Object> details = (Map<String, Object>) ((Map<String, Object>) components.get("databaseConnections"))
                .get("details");
        assertThat(details.get("AUDIT_DB").toString()).startsWith("UP");
        assertThat(details.get("LOG_DB").toString()).startsWith("UP");
    }
}
