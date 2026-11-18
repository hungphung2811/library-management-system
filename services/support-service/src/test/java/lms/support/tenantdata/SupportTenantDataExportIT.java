package lms.support.tenantdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import lms.database.core.DatabaseConnectionRegistry;
import lms.support.audit.integration.AppRoleDatabase;
import lms.support.tenantdata.application.SupportTenantDataTables;
import lms.tenant.export.ExportTable;
import lms.tenant.export.TenantDataExporter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ADR-0031 F1: exporter support trên PostgreSQL thật, AUDIT_DB kết nối bằng vai trò audit_app (NOBYPASSRLS). Hai tenant
 * có dữ liệu; tenant A chỉ xuất dòng của A (lô nhỏ buộc phân trang keyset, gồm khóa ghép), số dòng khớp COUNT(*),
 * địa chỉ nhận đầy đủ và khóa khử trùng không xuất hiện ở bất kỳ file nào, danh sách bảng/cột khớp schema thật.
 * Cần Docker: -Dtest=SupportTenantDataExportIT -DargLine=-Dapi.version=1.44.
 */
@Tag("docker")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.producer.properties.max.block.ms=300",
        "app.security.enabled=false",
        "lms.tenant-data.export.batch-size=2"
})
@Testcontainers
class SupportTenantDataExportIT {

    private static final String SECRET_ADDRESS = "nguoi.nhan.day.du@example.com";
    private static final String SECRET_KEY = "KHOA-KHU-TRUNG-NOI-BO-0123456789";

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

    @Autowired TenantDataExporter exporter;

    private static void seed(Connection c, UUID t, String tag, int rows) throws Exception {
        UUID user = UUID.randomUUID();
        for (int i = 0; i < rows; i++) {
            try (Statement s = c.createStatement()) {
                s.execute("INSERT INTO notifications (id, tenant_id, event_id, user_id, type, message, created_at) "
                        + "VALUES ('" + UUID.randomUUID() + "','" + t + "','" + UUID.randomUUID() + "','" + user
                        + "','LoanOverdue','Sách quá hạn, \"" + tag + "\" " + i + "', now())");
                s.execute("INSERT INTO audit_logs (id, tenant_id, event_id, event_type, aggregate_id, occurred_at, "
                        + "recorded_at, payload) VALUES ('" + UUID.randomUUID() + "','" + t + "','"
                        + UUID.randomUUID() + "','BookBorrowedEvent','agg-" + tag + "-" + i
                        + "', now(), now(), '{\"ghi chú\":\"" + tag + "\"}')");
                s.execute("INSERT INTO notification_deliveries (id, tenant_id, event_id, event_type, "
                        + "notification_type, category, channel, recipient_user_id, recipient_key, recipient_address, "
                        + "recipient_address_masked, params, status, next_attempt_at, created_at, updated_at) VALUES ('"
                        + UUID.randomUUID() + "','" + t + "','" + UUID.randomUUID() + "','E','ORDER_PLACED','ORDER',"
                        + "'EMAIL','" + user + "','" + SECRET_KEY + i + "','" + SECRET_ADDRESS + "','n***@e***.com',"
                        + "'{\"order_no\":\"DH-" + tag + "\"}'::jsonb,'SENT', now(), now(), now())");
            }
        }
        try (Statement s = c.createStatement()) {
            s.execute("INSERT INTO notification_templates (id, tenant_id, notification_type, channel, version, subject, "
                    + "body, active, created_at) VALUES ('" + UUID.randomUUID() + "','" + t
                    + "','ORDER_PLACED','EMAIL', 1, 'Chủ đề " + tag + "', 'Nội dung', true, now())");
            for (String ch : new String[] {"EMAIL", "IN_APP", "SMS"}) {
                s.execute("INSERT INTO notification_channel_settings (tenant_id, notification_type, channel, enabled, "
                        + "updated_at) VALUES ('" + t + "','ORDER_PLACED','" + ch + "', true, now())");
            }
            for (String cat : new String[] {"ORDER", "FINE", "LOAN_REMINDER"}) {
                s.execute("INSERT INTO notification_preferences (tenant_id, user_id, category, channel, enabled, "
                        + "updated_at) VALUES ('" + t + "','" + user + "','" + cat + "','EMAIL', false, now())");
            }
        }
    }

    private static long count(Connection c, String table, UUID tenant) throws Exception {
        try (PreparedStatement p = c.prepareStatement("SELECT count(*) FROM " + table + " WHERE tenant_id = ?")) {
            p.setObject(1, tenant);
            try (ResultSet rs = p.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private Map<String, String> exportZip(UUID tenant) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exporter.export(tenant, out);
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                files.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    private static String csvName(ExportTable t) {
        return t.splitEveryRows() > 0 ? t.name() + "_001.csv" : t.name() + ".csv";
    }

    @Test
    void tenantAChiXuatDongCuaASoDongKhopCountVaKhongLoDiaChi() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        try (Connection c = AppRoleDatabase.superuser(postgres)) {
            seed(c, a, "AAA", 5);
            seed(c, b, "BBB", 3);
        }

        Map<String, String> files = exportZip(a);

        assertThat(files.keySet()).last().isEqualTo("_meta.json");
        String meta = files.get("_meta.json");
        try (Connection c = AppRoleDatabase.superuser(postgres)) {
            for (ExportTable t : SupportTenantDataTables.TABLES) {
                String name = t.splitEveryRows() > 0 ? t.name() + "_001" : t.name();
                String csv = files.get(csvName(t));
                assertThat(csv).as(t.name()).startsWith("﻿" + String.join(",", t.columns()) + "\r\n");
                assertThat(meta).as(t.name()).contains("{\"name\":\"" + name + "\",\"file\":\"" + name
                        + ".csv\",\"rows\":" + count(c, t.name(), a) + ",");
                assertThat(csv).as(t.name() + " không chứa dữ liệu tenant B").doesNotContain("BBB");
            }
        }
        for (Map.Entry<String, String> f : files.entrySet()) {
            assertThat(f.getValue()).as(f.getKey() + " không lộ địa chỉ đầy đủ/khóa khử trùng")
                    .doesNotContain(SECRET_ADDRESS).doesNotContain(SECRET_KEY);
        }
        assertThat(files.get("notification_deliveries.csv")).contains("n***@e***.com")
                .contains("\"{\"\"order_no\"\": \"\"DH-AAA\"\"}\"");
        assertThat(files.get("notifications.csv")).contains("\"Sách quá hạn, \"\"AAA\"\" 4\"");
        assertThat(files.get("audit_logs_001.csv")).contains("agg-AAA-4").contains("ghi chú");
        assertThat(meta).contains("notification_deliveries.recipient_address").contains("mongo.raw_events");

        Map<String, String> filesB = exportZip(b);
        assertThat(filesB.get("notifications.csv")).contains("BBB").doesNotContain("AAA");
        assertThat(filesB.get("_meta.json")).contains("\"name\":\"notifications\",\"file\":\"notifications.csv\","
                + "\"rows\":3,");
    }

    @Test
    void tenantKhongCoDuLieuXuatToanBangRong() throws Exception {
        Map<String, String> files = exportZip(UUID.randomUUID());
        for (ExportTable t : SupportTenantDataTables.TABLES) {
            assertThat(files.get(csvName(t))).isEqualTo("﻿" + String.join(",", t.columns()) + "\r\n");
        }
    }

    @Test
    void danhSachBangVaCotKhopSchemaThat() throws Exception {
        Set<String> exported = new HashSet<>();
        SupportTenantDataTables.TABLES.forEach(t -> exported.add(t.name()));
        SupportTenantDataTables.EXCLUDED.forEach(e -> exported.add(e.target()));
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement()) {
            Set<String> tenantTables = new HashSet<>();
            try (ResultSet rs = s.executeQuery("SELECT table_name FROM information_schema.columns "
                    + "WHERE table_schema = 'public' AND column_name = 'tenant_id'")) {
                while (rs.next()) tenantTables.add(rs.getString(1));
            }
            assertThat(exported).as("bảng có tenant_id phải được khai xuất hoặc loại có lý do")
                    .containsAll(tenantTables);
            for (ExportTable t : SupportTenantDataTables.TABLES) {
                Set<String> dbColumns = new HashSet<>();
                try (ResultSet rs = s.executeQuery("SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = '" + t.name() + "'")) {
                    while (rs.next()) dbColumns.add(rs.getString(1));
                }
                dbColumns.remove("tenant_id");
                Set<String> accounted = new HashSet<>(t.columns());
                t.excludedColumns().forEach(e -> accounted.add(e.target().substring(t.name().length() + 1)));
                assertThat(accounted).as(t.name() + ": mọi cột thật phải xuất hoặc loại có lý do").isEqualTo(dbColumns);
            }
        }
    }
}
