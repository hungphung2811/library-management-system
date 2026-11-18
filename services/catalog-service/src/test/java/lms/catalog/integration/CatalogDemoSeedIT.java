package lms.catalog.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import lms.catalog.catalog.domain.ReferenceRepository;
import lms.catalog.catalog.domain.TitleRepository;
import lms.catalog.catalog.domain.WorkRepository;
import lms.catalog.catalog.infrastructure.demo.CatalogDemoSeedStep;
import lms.catalog.inventory.domain.BookCopyRepository;
import lms.catalog.inventory.infrastructure.demo.InventoryDemoSeedStep;
import lms.demo.plan.DemoIds;
import lms.demo.plan.DemoPlan;
import lms.demo.runner.DemoSeedContext;
import lms.demo.runner.DemoSeedStep;
import lms.security.permission.PermissionKeyRegistrar;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionOperations;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Seed demo của catalog (ADR-0018) trên Postgres thật, ứng dụng chạy bằng vai trò catalog_app (chịu RLS): hai bước seed
 * ghi đúng số lượng/trạng thái cho tenant demo, chạy lại không nhân đôi, tenant khác không thấy gì.
 * Cần Docker: mvn test -Dtest=CatalogDemoSeedIT -DargLine=-Dapi.version=1.44 -Dsurefire.failIfNoSpecifiedTests=false.
 * Dựng bước seed thủ công vì bean của chúng chỉ có khi bật profile demo-seed (runner sẽ thoát tiến trình nên không dùng trong test).
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/catalog"})
@Testcontainers
class CatalogDemoSeedIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    PermissionKeyRegistrar permissionKeyRegistrar;

    @Autowired
    ReferenceRepository references;
    @Autowired
    TitleRepository titles;
    @Autowired
    WorkRepository works;
    @Autowired
    BookCopyRepository copies;
    @Autowired
    TransactionOperations tx;

    private long count(String sql) throws Exception {
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void seedGhiDungChayLaiKhongNhanDoiVaChiThuocTenantDemo() throws Exception {
        UUID demo = UUID.randomUUID();
        DemoPlan plan = DemoPlan.generate(LocalDate.now());
        DemoSeedContext context = new DemoSeedContext(demo, plan, "x", Clock.systemDefaultZone());
        DemoSeedStep catalogStep = new CatalogDemoSeedStep(references, titles, works, tx);
        DemoSeedStep inventoryStep = new InventoryDemoSeedStep(copies, tx);

        DemoSeedStep.SeedResult t1 = TenantContext.callAs(demo, () -> catalogStep.run(context));
        DemoSeedStep.SeedResult c1 = TenantContext.callAs(demo, () -> inventoryStep.run(context));

        assertThat(t1.skipped()).isZero();
        assertThat(c1).isEqualTo(DemoSeedStep.SeedResult.of(plan.copies().size(), 0));
        String of = " WHERE tenant_id = '" + demo + "'";
        assertThat(count("SELECT count(*) FROM titles" + of)).isEqualTo(plan.titles().size()).isGreaterThanOrEqualTo(60);
        assertThat(count("SELECT count(*) FROM book_copies" + of)).isEqualTo(plan.copies().size()).isGreaterThanOrEqualTo(150);
        assertThat(count("SELECT count(*) FROM title_authors" + of)).isEqualTo(plan.titles().size());
        assertThat(count("SELECT count(*) FROM categories" + of)).isEqualTo(8);
        // mô hình tác phẩm - ấn bản (ADR-0026): mọi ấn bản gắn một tác phẩm, 64 ấn bản gốc là tác phẩm 1:1 id md5
        assertThat(count("SELECT count(*) FROM works" + of)).isEqualTo(plan.works().size());
        assertThat(count("SELECT count(*) FROM series" + of)).isEqualTo(plan.series().size());
        assertThat(count("SELECT count(*) FROM work_authors" + of)).isEqualTo(plan.works().size());
        assertThat(count("SELECT count(*) FROM titles" + of + " AND work_id IS NULL")).isZero();
        assertThat(count("SELECT count(*) FROM titles" + of + " AND work_id = md5('lms-work:' || id::text)::uuid"))
                .isEqualTo(plan.titles().stream().filter(t -> t.workId().equals(DemoIds.legacyWork(t.id()))).count())
                .isGreaterThanOrEqualTo(64);
        assertThat(count("SELECT count(*) FROM works" + of + " AND kind = 'COLLECTION'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM works w WHERE w.tenant_id = '" + demo
                + "' AND (SELECT count(*) FROM titles t WHERE t.work_id = w.id) >= 2")).isGreaterThanOrEqualTo(4);
        assertThat(count("SELECT count(*) FROM titles" + of + " AND part_number IS NOT NULL")).isEqualTo(2);
        for (DemoPlan.CopyStatus s : DemoPlan.CopyStatus.values()) {
            long expected = plan.copies().stream().filter(x -> x.status() == s).count();
            assertThat(count("SELECT count(*) FROM book_copies" + of + " AND status = '" + s + "'")).isEqualTo(expected);
        }
        assertThat(count("SELECT count(*) FROM titles WHERE tenant_id <> '" + demo + "'")).isZero();

        long worksBefore = count("SELECT count(*) FROM works");
        long seriesBefore = count("SELECT count(*) FROM series");
        long titlesBefore = count("SELECT count(*) FROM titles");
        long copiesBefore = count("SELECT count(*) FROM book_copies");
        DemoSeedStep.SeedResult t2 = TenantContext.callAs(demo, () -> catalogStep.run(context));
        DemoSeedStep.SeedResult c2 = TenantContext.callAs(demo, () -> inventoryStep.run(context));
        assertThat(t2.created()).isZero();
        assertThat(c2.created()).isZero();
        assertThat(c2.skipped()).isEqualTo(plan.copies().size());
        assertThat(count("SELECT count(*) FROM titles")).isEqualTo(titlesBefore);
        assertThat(count("SELECT count(*) FROM works")).isEqualTo(worksBefore);
        assertThat(count("SELECT count(*) FROM series")).isEqualTo(seriesBefore);
        assertThat(count("SELECT count(*) FROM book_copies")).isEqualTo(copiesBefore);

        // RLS: tenant khác không thấy dữ liệu demo; vai trò app không có tenant thì không thấy dòng nào
        UUID other = UUID.randomUUID();
        assertThat(TenantContext.callAs(other, () -> titles.search(null))).isEmpty();
        try (Connection c = AppRoleDatabase.appRole(postgres); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM titles")) {
            rs.next();
            assertThat(rs.getLong(1)).isZero();
        }
    }
}
