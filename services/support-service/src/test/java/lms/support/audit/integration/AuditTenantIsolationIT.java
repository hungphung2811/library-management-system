package lms.support.audit.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.database.api.MongoConnection;
import lms.database.core.DatabaseConnectionRegistry;
import lms.support.audit.application.AuditLogResponse;
import lms.support.audit.application.QueryAuditLogsUseCase;
import lms.support.audit.application.RecordAuditEventCommand;
import lms.support.audit.application.RecordAuditEventUseCase;
import lms.support.audit.domain.AuditLogCriteria;
import lms.support.audit.infrastructure.messaging.AuditEventConsumer;
import lms.support.audit.infrastructure.persistence.MongoRawEventArchive;
import lms.tenant.TenantContext;
import lms.tenant.TenantRequiredException;
import org.bson.Document;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Cách ly audit theo tenant trên PostgreSQL và MongoDB thật. Ứng dụng kết nối AUDIT_DB bằng vai trò audit_app
 * (NOBYPASSRLS) nên RLS có hiệu lực; Flyway của test chạy bằng superuser (chủ bảng, FORCE RLS). Cần Docker.
 * Chạy: mvn test -Dtest=AuditTenantIsolationIT -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Dapi.version=1.44
 */
@Tag("docker")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.producer.properties.max.block.ms=300",
        "app.security.enabled=false"
})
@Testcontainers
@Import(TestTenantFilterConfiguration.class)
class AuditTenantIsolationIT {

    static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Container
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

    @DynamicPropertySource
    static void databases(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
        registry.add("database.connections.LOG_DB.uri", () -> mongo.getReplicaSetUrl("audit_logs"));
    }

    @TestConfiguration
    static class AuditFlywayTestConfiguration {
        @Bean(name = "auditFlyway", initMethod = "migrate")
        @DependsOn("databaseLifecycleManager")
        Flyway auditFlyway(DatabaseConnectionRegistry registry) {
            return Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration/audit")
                    .load();
        }
    }

    @Autowired TestRestTemplate rest;
    @Autowired DatabaseConnectionRegistry registry;
    @Autowired RecordAuditEventUseCase record;
    @Autowired QueryAuditLogsUseCase query;
    @Autowired AuditEventConsumer consumer;
    @Autowired MongoRawEventArchive archive;

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private static String envelope(UUID eventId, String aggregateId, UUID tenantId) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"BookBorrowedEvent\",\"aggregateId\":\"" + aggregateId
                + "\",\"occurredAt\":\"2026-01-01T00:00:00Z\",\"version\":1,\"payload\":{\"k\":\"v\"}"
                + (tenantId == null ? "" : ",\"tenantId\":\"" + tenantId + "\"") + "}";
    }

    private JdbcTemplate superuserJdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword()));
    }

    private List<AuditLogResponse> items(UUID tenant, String aggregateId) {
        return TenantContext.callAs(tenant,
                () -> query.execute(new AuditLogCriteria(aggregateId, null, 0, 50)).items());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> restGet(UUID tenant, String aggregateId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(TestTenantFilterConfiguration.HEADER, tenant.toString());
        return rest.exchange("/internal/audit-logs?aggregateId=" + aggregateId, HttpMethod.GET,
                new HttpEntity<>(headers), Map.class).getBody();
    }

    @Test
    void auditCuaTenantBKhongThayTuTenantAOPostgresVaRest() {
        String aggregate = "agg-" + UUID.randomUUID();
        UUID eventA = UUID.randomUUID();
        UUID eventB = UUID.randomUUID();
        consumer.onEvent(envelope(eventA, aggregate, TENANT_A), "borrowing.events");
        consumer.onEvent(envelope(eventB, aggregate, TENANT_B), "borrowing.events");

        // Cùng aggregateId nhưng mỗi tenant chỉ thấy event của mình
        assertThat(items(TENANT_A, aggregate)).extracting(AuditLogResponse::eventId).containsExactly(eventA);
        assertThat(items(TENANT_B, aggregate)).extracting(AuditLogResponse::eventId).containsExactly(eventB);
        // Qua REST (đường dùng thật của token tenant A / B)
        assertThat(restGet(TENANT_A, aggregate).get("total")).isEqualTo(1);
        assertThat(restGet(TENANT_B, aggregate).get("total")).isEqualTo(1);
        // Không có tenant: không thấy gì (fail-closed)
        assertThat(query.execute(new AuditLogCriteria(aggregate, null, 0, 50)).items()).isEmpty();
        // Dữ liệu thật sự nằm đúng tenant trong bảng
        assertThat(superuserJdbc().queryForObject("SELECT tenant_id FROM audit_logs WHERE event_id = ?", UUID.class,
                eventA)).isEqualTo(TENANT_A);
        assertThat(superuserJdbc().queryForObject("SELECT tenant_id FROM audit_logs WHERE event_id = ?", UUID.class,
                eventB)).isEqualTo(TENANT_B);
    }

    @Test
    void rlsKhoaDongKhiKetNoiAuditAppKhongDatTenant() throws Exception {
        UUID event = UUID.randomUUID();
        consumer.onEvent(envelope(event, "agg-rls-" + UUID.randomUUID(), TENANT_A), "borrowing.events");

        // Kết nối thô bằng vai trò ứng dụng, không đặt app.tenant_id: bảng có dữ liệu nhưng không thấy dòng nào
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), AppRoleDatabase.APP_ROLE,
                AppRoleDatabase.APP_ROLE);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM audit_logs")) {
            rs.next();
            assertThat(rs.getInt(1)).isZero();
        }
        assertThat(superuserJdbc().queryForObject("SELECT COUNT(*) FROM audit_logs WHERE event_id = ?", Integer.class,
                event)).isEqualTo(1);
    }

    @Test
    void banThoMongoCoTenantIdVaTruyVanLocTheoTenant() {
        String aggregate = "agg-mongo-" + UUID.randomUUID();
        UUID eventA = UUID.randomUUID();
        UUID eventB = UUID.randomUUID();
        consumer.onEvent(envelope(eventA, aggregate, TENANT_A), "borrowing.events");
        consumer.onEvent(envelope(eventB, aggregate, TENANT_B), "borrowing.events");

        Document raw = registry.require("LOG_DB", MongoConnection.class).mongoTemplate().getCollection("raw_events")
                .find(new Document("eventId", eventA.toString())).first();
        assertThat(raw).isNotNull();
        assertThat(raw.getString("tenantId")).isEqualTo(TENANT_A.toString());

        TenantContext.set(TENANT_A);
        assertThat(archive.findRawEnvelope(eventA)).isPresent();
        assertThat(archive.findRawEnvelope(eventB)).isEmpty();
        TenantContext.set(TENANT_B);
        assertThat(archive.findRawEnvelope(eventB)).isPresent();
        assertThat(archive.findRawEnvelope(eventA)).isEmpty();
        TenantContext.clear();
        assertThat(archive.findRawEnvelope(eventA)).isEmpty();
    }

    @Test
    void eventCungIdCuaHaiTenantKhongGhiDeBanThoCuaNhau() {
        UUID shared = UUID.randomUUID();
        // Cùng eventId ở hai tenant: Mongo giữ cả hai bản thô (audit Postgres giữ event_id duy nhất toàn cục)
        TenantContext.runAs(TENANT_A, () -> archive.archive(new lms.support.audit.application.ArchiveRawEventCommand(
                shared, "t", "E", "agg", Instant.now(), "{\"of\":\"A\"}")));
        TenantContext.runAs(TENANT_B, () -> archive.archive(new lms.support.audit.application.ArchiveRawEventCommand(
                shared, "t", "E", "agg", Instant.now(), "{\"of\":\"B\"}")));

        assertThat(TenantContext.callAs(TENANT_A, () -> archive.findRawEnvelope(shared))).hasValue("{\"of\":\"A\"}");
        assertThat(TenantContext.callAs(TENANT_B, () -> archive.findRawEnvelope(shared))).hasValue("{\"of\":\"B\"}");
    }

    @Test
    void eventKhongCoTenantIdKhongGhiGiVaKhongLamConsumerLoi() {
        UUID eventId = UUID.randomUUID();

        assertThatCode(() -> consumer.onEvent(envelope(eventId, "agg-notenant", null), "borrowing.events"))
                .doesNotThrowAnyException();

        assertThat(superuserJdbc().queryForObject("SELECT COUNT(*) FROM audit_logs WHERE event_id = ?", Integer.class,
                eventId)).isZero();
        assertThat(registry.require("LOG_DB", MongoConnection.class).mongoTemplate().getCollection("raw_events")
                .countDocuments(new Document("eventId", eventId.toString()))).isZero();
    }

    @Test
    void ghiAuditKhongCoTenantContextBiTuChoi() {
        assertThatThrownBy(() -> record.execute(new RecordAuditEventCommand(UUID.randomUUID(), "E", "agg",
                Instant.now(), "{}"))).isInstanceOf(TenantRequiredException.class);
    }

    @Test
    void truyVanNativeLocDungTenantKhiTrungEventIdVaTrungAggregate() {
        String aggregate = "agg-native-" + UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        // Event giao lại cho tenant A: idempotent; tenant B gửi cùng eventId bị bỏ qua (event_id unique toàn cục),
        // và không làm lộ hay ghi đè dòng của tenant A.
        consumer.onEvent(envelope(eventId, aggregate, TENANT_A), "borrowing.events");
        consumer.onEvent(envelope(eventId, aggregate, TENANT_A), "borrowing.events");
        consumer.onEvent(envelope(eventId, aggregate, TENANT_B), "borrowing.events");

        assertThat(items(TENANT_A, aggregate)).hasSize(1);
        assertThat(items(TENANT_B, aggregate)).isEmpty();
        assertThat(superuserJdbc().queryForObject("SELECT COUNT(*) FROM audit_logs WHERE event_id = ?", Integer.class,
                eventId)).isEqualTo(1);
    }

    @Test
    void salesEventsGhiAuditTheoTenantVaIdempotent() {
        String aggregate = "order-" + UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String message = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"OrderCompleted\",\"aggregateId\":\""
                + aggregate + "\",\"occurredAt\":\"2026-01-01T00:00:00Z\",\"version\":1,\"payload\":{\"action\":"
                + "\"ORDER_CREATED\",\"aggregateType\":\"ORDER\",\"actorId\":\"" + UUID.randomUUID()
                + "\",\"data\":{\"total\":150000}},\"tenantId\":\"" + TENANT_A + "\"}";

        consumer.onEvent(message, "sales.events");
        consumer.onEvent(message, "sales.events");

        assertThat(items(TENANT_A, aggregate)).singleElement().satisfies(log -> {
            assertThat(log.eventType()).isEqualTo("OrderCompleted");
            assertThat(log.payload()).contains("ORDER_CREATED", "actorId");
        });
        assertThat(items(TENANT_B, aggregate)).isEmpty();
    }
}
