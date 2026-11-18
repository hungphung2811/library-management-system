package lms.catalog.integration;

import static lms.catalog.catalog.unit.CatalogImportRows.row;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lms.catalog.catalog.application.imports.CatalogImportSchemas;
import lms.catalog.catalog.application.imports.TitleImportHandler;
import lms.imports.core.ImportHandler;
import lms.imports.core.ImportLimits;
import lms.imports.core.ImportSessionStatus;
import lms.imports.core.ImportSessionStore;
import lms.imports.core.ImportViews;
import lms.imports.engine.ImportActor;
import lms.imports.engine.ImportWorkflow;
import lms.imports.engine.InMemoryImportSessionStore;
import lms.security.permission.PermissionKeyRegistrar;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Kiểm thử độc lập nhập đầu sách trên Postgres thật (vai trò catalog_app, RLS): giá trị hợp lệ ở preview nhưng vượt
 * giới hạn cột DB không được làm hỏng cả lần nhập; trùng ISBN không rò giữa tenant.
 * Chạy: mvn -pl services/catalog-service -am test -Dtest=CatalogImportEdgeIT -DargLine=-Dapi.version=1.44
 * -Dsurefire.failIfNoSpecifiedTests=false -Dspring.kafka.bootstrap-servers=localhost:1
 * -Dspring.kafka.consumer.group-id=lms-it-isolated
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/catalog",
        "app.security.enabled=true",
        "lms.security.permission.enabled=true",
        "spring.kafka.producer.properties.max.block.ms=200"})
@Testcontainers
class CatalogImportEdgeIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    @TestConfiguration
    static class Config {
        @Bean
        ImportSessionStore importSessionStore() {
            return new InMemoryImportSessionStore(ImportLimits.defaults(), Clock.systemUTC());
        }
    }

    @Autowired ImportWorkflow workflow;
    @Autowired TitleImportHandler titles;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    private final UUID tenant = UUID.randomUUID();
    private final ImportActor actor = new ImportActor(tenant, "alice", Set.of("LENDING"), false);

    private long count(String sql) throws SQLException {
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private ImportViews.SessionView confirmAndWait(UUID tenantId, ImportActor who, ImportHandler handler, UUID importId)
            throws Exception {
        TenantContext.runAs(tenantId, () -> workflow.confirm(handler, who, importId));
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            ImportViews.SessionView v = TenantContext.callAs(tenantId, () -> workflow.get(handler, who, importId));
            if (v.status() == ImportSessionStatus.COMMITTED || v.status() == ImportSessionStatus.COMMIT_FAILED) return v;
            Thread.sleep(25);
        }
        throw new IllegalStateException("Confirm không xong trong 30 giây");
    }

    /**
     * cover_price là NUMERIC(12,2): 99999999999 (1e11) hợp lệ với domain và preview nhưng DB từ chối. Mong đợi: hoặc
     * preview báo INVALID, hoặc chỉ dòng đó FAILED còn dòng tốt vẫn được ghi (ADR-0024 3.6: lỗi một dòng không làm hỏng cả chunk).
     */
    @Test
    void giaBiaVuotCotDbKhongLamHongCaLanNhap() throws Exception {
        byte[] file = lms.catalog.catalog.unit.CatalogImportRows.xlsx(CatalogImportSchemas.titles(), java.util.Map.of(
                "DauSach", List.of(
                        row("isbn", "EDGE-GOOD-1", "name", "Sách tốt 1", "author", "A", "coverPrice", "1000"),
                        row("isbn", "EDGE-BIG", "name", "Giá khổng lồ", "author", "A", "coverPrice", "99999999999"),
                        row("isbn", "EDGE-GOOD-2", "name", "Sách tốt 2", "author", "A", "coverPrice", "2000"))));
        ImportViews.SessionView up = TenantContext.callAs(tenant, () -> workflow.upload(titles, actor, "t.xlsx", file));
        int previewValid = up.totals().valid();

        ImportViews.SessionView done = confirmAndWait(tenant, actor, titles, up.importId());

        long good = count("select count(*) from titles where tenant_id = '" + tenant + "' and isbn like 'EDGE-GOOD-%'");
        assertThat(done.status()).as("preview valid=%d, kết quả=%s", previewValid, done.result()).isEqualTo(ImportSessionStatus.COMMITTED);
        assertThat(good).as("hai đầu sách tốt phải được ghi dù một dòng vượt cột").isEqualTo(2);
    }

    @Test
    void trungIsbnKhongRoGiuaCacTenant() throws Exception {
        UUID other = UUID.randomUUID();
        ImportActor otherActor = new ImportActor(other, "bob", Set.of("LENDING"), false);
        byte[] file = lms.catalog.catalog.unit.CatalogImportRows.xlsx(CatalogImportSchemas.titles(), java.util.Map.of(
                "DauSach", List.of(row("isbn", "EDGE-ISO-1", "name", "Chung ISBN", "author", "A", "coverPrice", "1000"))));
        ImportViews.SessionView a = TenantContext.callAs(tenant, () -> workflow.upload(titles, actor, "a.xlsx", file));
        confirmAndWait(tenant, actor, titles, a.importId());

        ImportViews.SessionView b = TenantContext.callAs(other, () -> workflow.upload(titles, otherActor, "b.xlsx", file));
        assertThat(b.totals().duplicate()).as("ISBN của tenant khác không được coi là trùng").isZero();
        assertThat(b.totals().valid()).isEqualTo(1);
        ImportViews.SessionView done = confirmAndWait(other, otherActor, titles, b.importId());
        assertThat(done.result().created()).isEqualTo(1);
        assertThat(count("select count(*) from titles where isbn = 'EDGE-ISO-1'")).isEqualTo(2);

        // phiên của tenant A không truy cập được bằng tenant B (cùng importId)
        org.junit.jupiter.api.Assertions.assertThrows(lms.imports.core.ImportException.class,
                () -> TenantContext.callAs(other, () -> workflow.get(titles, otherActor, a.importId())));
    }
}
