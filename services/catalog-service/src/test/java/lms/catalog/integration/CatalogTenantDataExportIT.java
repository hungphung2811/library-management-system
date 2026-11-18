package lms.catalog.integration;

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
import lms.catalog.tenantdata.application.CatalogTenantDataTables;
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
 * ADR-0031 F1: exporter catalog trên PostgreSQL thật, ứng dụng chạy bằng vai trò catalog_app (chịu RLS). Hai tenant có
 * dữ liệu; tenant A chỉ xuất dòng của A (lô nhỏ để buộc phân trang keyset), số dòng khớp COUNT(*), danh sách bảng/cột xuất
 * khớp schema thật. Cần Docker: -Dtest=CatalogTenantDataExportIT -DargLine=-Dapi.version=1.44.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/catalog",
        "app.security.enabled=true",
        "lms.security.permission.enabled=true",
        "lms.tenant-data.export.batch-size=2"})
@AutoConfigureMockMvc
@Testcontainers
class CatalogTenantDataExportIT {

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

    private void seed(Connection c, UUID t, String tag, int titles) throws Exception {
        UUID category = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        UUID publisher = UUID.randomUUID();
        UUID shelf = UUID.randomUUID();
        UUID series = UUID.randomUUID();
        UUID work = UUID.randomUUID();
        try (Statement s = c.createStatement()) {
            s.execute("INSERT INTO categories (id, tenant_id, name, name_key) VALUES ('" + category + "','" + t
                    + "','Cat " + tag + "','cat " + tag + "')");
            s.execute("INSERT INTO authors (id, tenant_id, name, name_key) VALUES ('" + author + "','" + t
                    + "','Nguyễn Nhật Ánh " + tag + "','nna " + tag + "')");
            s.execute("INSERT INTO publishers (id, tenant_id, name, name_key) VALUES ('" + publisher + "','" + t
                    + "','NXB " + tag + "','nxb " + tag + "')");
            s.execute("INSERT INTO shelf_locations (id, tenant_id, code, code_key) VALUES ('" + shelf + "','" + t
                    + "','K-" + tag + "','k-" + tag + "')");
            s.execute("INSERT INTO series (id, tenant_id, name, name_key) VALUES ('" + series + "','" + t
                    + "','Series " + tag + "','series " + tag + "')");
            s.execute("INSERT INTO works (id, tenant_id, title, author) VALUES ('" + work + "','" + t
                    + "','Work " + tag + "','Tac gia " + tag + "')");
            s.execute("INSERT INTO work_authors (work_id, author_id, tenant_id) VALUES ('" + work + "','" + author
                    + "','" + t + "')");
        }
        for (int i = 0; i < titles; i++) {
            UUID title = UUID.randomUUID();
            try (PreparedStatement p = c.prepareStatement("INSERT INTO titles (id, tenant_id, name, author, isbn, "
                    + "cover_price, tags, publisher_id, category_id) VALUES (?,?,?,?,?,?,?,?,?)")) {
                p.setObject(1, title);
                p.setObject(2, t);
                p.setString(3, "Sách \"" + tag + "\", tập " + i + "\nxuống dòng");
                p.setString(4, "Tác giả " + tag);
                p.setString(5, "ISBN-" + tag + "-" + i);
                p.setBigDecimal(6, new java.math.BigDecimal("125000.50"));
                p.setArray(7, c.createArrayOf("text", new String[] {"thieu nhi", "tag \"x\""}));
                p.setObject(8, publisher);
                p.setObject(9, category);
                p.executeUpdate();
            }
            try (Statement s = c.createStatement()) {
                s.execute("INSERT INTO title_authors (title_id, author_id, tenant_id) VALUES ('" + title + "','"
                        + author + "','" + t + "')");
                s.execute("INSERT INTO book_copies (id, tenant_id, title_id, barcode, status, shelf_location_id) "
                        + "VALUES ('" + UUID.randomUUID() + "','" + t + "','" + title + "','BC-" + tag + "-" + i
                        + "','AVAILABLE','" + shelf + "')");
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
            for (ExportTable t : CatalogTenantDataTables.TABLES) {
                String csv = files.get(t.name() + ".csv");
                assertThat(csv).as(t.name()).startsWith("﻿" + String.join(",", t.columns()) + "\r\n");
                assertThat(meta).as(t.name()).contains("{\"name\":\"" + t.name() + "\",\"file\":\"" + t.name()
                        + ".csv\",\"rows\":" + count(c, t.name(), a) + ",");
                assertThat(csv).as(t.name() + " không chứa dữ liệu tenant B").doesNotContain("BBB");
            }
        }
        assertThat(files.get("titles.csv")).contains("ISBN-AAA-4").contains("\"[\"\"thieu nhi\"\",\"\"tag \\\"\"x\\\"\"\"\"]\"")
                .contains("125000.50").contains("Sách \"\"AAA\"\", tập 0\nxuống dòng");
        assertThat(files.get("book_copies.csv")).contains("BC-AAA-0").contains("BC-AAA-4");
        assertThat(files.get("authors.csv")).contains("Nguyễn Nhật Ánh AAA");

        Map<String, String> filesB = exportZip(b);
        assertThat(filesB.get("titles.csv")).contains("ISBN-BBB-2").doesNotContain("AAA");
        assertThat(filesB.get("_meta.json")).contains("\"name\":\"titles\",\"file\":\"titles.csv\",\"rows\":3,");
    }

    @Test
    void tenantKhongCoDuLieuXuatToanBangRong() throws Exception {
        Map<String, String> files = exportZip(UUID.randomUUID());
        for (ExportTable t : CatalogTenantDataTables.TABLES) {
            assertThat(files.get(t.name() + ".csv")).isEqualTo("﻿" + String.join(",", t.columns()) + "\r\n");
        }
    }

    @Test
    void danhSachBangVaCotKhopSchemaThat() throws Exception {
        Set<String> exported = new HashSet<>();
        CatalogTenantDataTables.TABLES.forEach(t -> exported.add(t.name()));
        CatalogTenantDataTables.EXCLUDED.forEach(e -> exported.add(e.target()));
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement()) {
            Set<String> tenantTables = new HashSet<>();
            try (ResultSet rs = s.executeQuery("SELECT table_name FROM information_schema.columns "
                    + "WHERE table_schema = 'public' AND column_name = 'tenant_id'")) {
                while (rs.next()) tenantTables.add(rs.getString(1));
            }
            assertThat(exported).as("bảng có tenant_id phải được khai xuất hoặc loại có lý do")
                    .containsAll(tenantTables);
            for (ExportTable t : CatalogTenantDataTables.TABLES) {
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
