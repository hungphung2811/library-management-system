package lms.circulation.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import lms.circulation.tenantdata.application.CirculationTenantDataTables;
import lms.security.permission.PermissionKeyRegistrar;
import lms.tenant.export.ExportTable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ADR-0031 F1: exporter circulation trên PostgreSQL thật, ứng dụng chạy bằng vai trò circulation_app (chịu RLS). Hai
 * tenant có dữ liệu; tenant A chỉ xuất dòng của A (lô nhỏ buộc phân trang keyset, gồm bảng không khóa duy nhất phân trang
 * đọc nguyên nhóm dòng trùng khóa nghiệp vụ), số dòng khớp COUNT(*), danh sách bảng/cột khớp schema thật.
 * Cần Docker: -Dtest=CirculationTenantDataExportIT -DargLine=-Dapi.version=1.44.
 */
@SpringBootTest(properties = {
        "spring.profiles.active=it",
        "app.security.enabled=true",
        "lms.security.permission.enabled=true",
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.producer.properties.max.block.ms=200",
        "app.reservation.expire-cron=-",
        "lms.tenant-data.export.batch-size=2"})
@AutoConfigureMockMvc
@Testcontainers
class CirculationTenantDataExportIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    private static RequestPostProcessor tenantData(UUID tenant) {
        return jwt().jwt(j -> j.audience(List.of("lms-tenant-data")).subject("identity").claim("svc", "tenant-data")
                .claim("tenantId", tenant.toString()));
    }

    private static void seed(Connection c, UUID t, String tag, int borrowings) throws Exception {
        UUID reader = UUID.randomUUID();
        for (int i = 0; i < borrowings; i++) {
            UUID b = UUID.randomUUID();
            UUID copy = UUID.randomUUID();
            try (Statement s = c.createStatement()) {
                s.execute("INSERT INTO borrowings (id, tenant_id, user_id, borrow_date, due_date, renew_count, loan_days)"
                        + " VALUES ('" + b + "','" + t + "','" + reader + "', current_date - 20, current_date - 6, 1, 14)");
                // 2-3 dòng trùng (borrowing_id, book_copy_id), đều đã trả: bảng không có khóa duy nhất, phải đủ mọi dòng
                for (int k = 0; k < (i == 0 ? 3 : 2); k++) {
                    s.execute("INSERT INTO borrowing_items (borrowing_id, tenant_id, book_copy_id, return_date, "
                            + "item_condition, condition_note) VALUES ('" + b + "','" + t + "','" + copy
                            + "', current_date - 5, 'GOOD', 'ghi chú " + tag + ", \"ổn\"')");
                }
                s.execute("INSERT INTO fines (id, tenant_id, reader_id, borrowing_id, book_copy_id, reason, amount, "
                        + "status, created_at, assessment_note) VALUES ('" + UUID.randomUUID() + "','" + t + "','"
                        + reader + "','" + b + "','" + copy + "','LATE_RETURN', 5000.50, 'UNPAID', now(), 'phạt " + tag
                        + "')");
                s.execute("INSERT INTO reservations (id, tenant_id, reader_id, title_id, reserved_at, status) VALUES ('"
                        + UUID.randomUUID() + "','" + t + "','" + UUID.randomUUID() + "','" + UUID.randomUUID()
                        + "', now(), 'WAITING')");
                s.execute("INSERT INTO loan_reminders (id, tenant_id, borrowing_id, kind, due_date, seq, sent_on, "
                        + "event_id) VALUES ('" + UUID.randomUUID() + "','" + t + "','" + b
                        + "','OVERDUE', current_date - 6, 1, current_date, gen_random_uuid())");
            }
        }
        try (Statement s = c.createStatement()) {
            s.execute("INSERT INTO circulation_policies (id, tenant_id, version, loan_days, max_renewals, renewal_days, "
                    + "max_active_loans_per_reader, fine_per_day, grace_days, reservation_hold_days, updated_at) "
                    + "VALUES ('" + UUID.randomUUID() + "','" + t + "', 0, 14, 2, 14, 5, 5000, 0, 3, now())");
            s.execute("INSERT INTO damage_levels (id, tenant_id, code, label, rate, created_at, updated_at) VALUES ('"
                    + UUID.randomUUID() + "','" + t + "','L" + tag + "','Nhẹ " + tag + "', 0.1, now(), now())");
            s.execute("INSERT INTO borrow_rules (reader_type, max_items, loan_days, tenant_id) VALUES ('STUDENT', 5, 14, '"
                    + t + "')");
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
        byte[] zip = mvc.perform(get("/internal/tenant-data/export").with(tenantData(tenant)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        Map<String, String> out = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                out.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    @Test
    void tenantAChiXuatDongCuaAVaSoDongKhopCount() throws Exception {
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
            for (ExportTable t : CirculationTenantDataTables.TABLES) {
                long expected = count(c, t.name(), a);
                String csv = files.get(t.name() + ".csv");
                assertThat(csv).as(t.name()).startsWith("﻿" + String.join(",", t.columns()) + "\r\n");
                assertThat(meta).as(t.name()).contains("{\"name\":\"" + t.name() + "\",\"file\":\"" + t.name()
                        + ".csv\",\"rows\":" + expected + ",");
                assertThat(csv).as(t.name() + " không chứa dữ liệu tenant B").doesNotContain("BBB");
            }
            // borrowing_items: 5 phiếu có 2-3 dòng trùng khóa nghiệp vụ = 11 dòng, không mất/lặp dòng nào khi lô nhỏ hơn nhóm
            assertThat(count(c, "borrowing_items", a)).isEqualTo(11);
        }
        assertThat(meta).contains("\"name\":\"borrowing_items\",\"file\":\"borrowing_items.csv\",\"rows\":11,");
        assertThat(files.get("borrowing_items.csv")).contains("\"ghi chú AAA, \"\"ổn\"\"\"");
        assertThat(files.get("fines.csv")).contains("5000.50").contains("phạt AAA");
        assertThat(meta).contains("{\"table\":\"known_tenants\"");

        Map<String, String> filesB = exportZip(b);
        assertThat(filesB.get("fines.csv")).contains("phạt BBB").doesNotContain("AAA");
        assertThat(filesB.get("_meta.json")).contains("\"name\":\"borrowings\",\"file\":\"borrowings.csv\",\"rows\":3,");
    }

    @Test
    void tenantKhongCoDuLieuXuatToanBangRong() throws Exception {
        Map<String, String> files = exportZip(UUID.randomUUID());
        for (ExportTable t : CirculationTenantDataTables.TABLES) {
            assertThat(files.get(t.name() + ".csv")).isEqualTo("﻿" + String.join(",", t.columns()) + "\r\n");
        }
    }

    @Test
    void danhSachBangVaCotKhopSchemaThat() throws Exception {
        Set<String> exported = new HashSet<>();
        CirculationTenantDataTables.TABLES.forEach(t -> exported.add(t.name()));
        CirculationTenantDataTables.EXCLUDED.forEach(e -> exported.add(e.target()));
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement()) {
            Set<String> tenantTables = new HashSet<>();
            try (ResultSet rs = s.executeQuery("SELECT table_name FROM information_schema.columns "
                    + "WHERE table_schema = 'public' AND column_name = 'tenant_id'")) {
                while (rs.next()) tenantTables.add(rs.getString(1));
            }
            assertThat(exported).as("bảng có tenant_id phải được khai xuất hoặc loại có lý do")
                    .containsAll(tenantTables);
            for (ExportTable t : CirculationTenantDataTables.TABLES) {
                Set<String> dbColumns = new HashSet<>();
                try (ResultSet rs = s.executeQuery("SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = '" + t.name() + "'")) {
                    while (rs.next()) dbColumns.add(rs.getString(1));
                }
                dbColumns.remove("tenant_id");
                assertThat(Set.copyOf(t.columns())).as(t.name() + ": cột xuất phải khớp cột thật").isEqualTo(dbColumns);
            }
        }
    }
}
