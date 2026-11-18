package lms.catalog.integration;

import static lms.catalog.catalog.unit.CatalogImportRows.row;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lms.catalog.catalog.application.imports.CatalogImportSchemas;
import lms.catalog.catalog.application.imports.ReferenceImportHandler;
import lms.catalog.catalog.application.imports.TitleImportHandler;
import lms.imports.core.ImportContext;
import lms.imports.core.ImportLimits;
import lms.imports.core.ImportRow;
import lms.imports.core.ImportSessionStatus;
import lms.imports.core.ImportSessionStore;
import lms.imports.core.ImportViews;
import lms.imports.engine.ImportActor;
import lms.imports.engine.ImportWorkflow;
import lms.imports.engine.InMemoryImportSessionStore;
import lms.security.permission.PermissionKeyRegistrar;
import lms.tenant.TenantContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
 * Nhập Excel (ADR-0024) trên Postgres thật bằng vai trò catalog_app (RLS fail-closed, JDBC batch bật): ghi chunk bằng
 * persist theo lô đúng tenant, ràng buộc unique, và SỐ CÂU SQL của commitChunk không tăng theo số dòng (không N+1,
 * không SELECT của merge). Phiên dùng InMemoryImportSessionStore (không cần Redis). Cần Docker. Chạy:
 * mvn -pl services/catalog-service -am test -Dtest=CatalogImportIT -DargLine=-Dapi.version=1.44
 * -Dsurefire.failIfNoSpecifiedTests=false -Dspring.kafka.bootstrap-servers=localhost:1
 * -Dspring.kafka.consumer.group-id=lms-it-isolated
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/catalog",
        "app.security.enabled=true",
        "lms.security.permission.enabled=true",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.kafka.producer.properties.max.block.ms=200"})
@Testcontainers
class CatalogImportIT {

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
    @Autowired @Qualifier("authorImportHandler") ReferenceImportHandler authors;
    @Autowired @Qualifier("categoryImportHandler") ReferenceImportHandler categories;
    @Autowired @Qualifier("shelfLocationImportHandler") ReferenceImportHandler shelves;
    @Autowired TitleImportHandler titles;
    @Autowired EntityManagerFactory emf;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    private final UUID tenant = UUID.randomUUID();
    private final ImportActor actor = new ImportActor(tenant, "alice", Set.of("LENDING"), false);

    private Statistics stats() {
        return emf.unwrap(SessionFactory.class).getStatistics();
    }

    private ImportContext ctx() {
        return new ImportContext(tenant, "alice", UUID.randomUUID(), Set.of("LENDING"), false);
    }

    private long count(String sql) throws SQLException {
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private ImportViews.SessionView confirmAndWait(lms.imports.core.ImportHandler handler, UUID importId) throws Exception {
        TenantContext.runAs(tenant, () -> workflow.confirm(handler, actor, importId));
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            ImportViews.SessionView v = workflow.get(handler, actor, importId);
            if (v.status() == ImportSessionStatus.COMMITTED || v.status() == ImportSessionStatus.COMMIT_FAILED) return v;
            Thread.sleep(25);
        }
        throw new IllegalStateException("Confirm không xong trong 30 giây");
    }

    private static List<ImportRow> authorRows(int n, String prefix) {
        List<ImportRow> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rows.add(ImportRow.of("TacGia", i + 2, Map.of("name", prefix + i, "birthYear", "1950")));
        }
        return rows;
    }

    /** Số câu SQL chuẩn bị khi ghi một chunk; cố định theo số dòng (cộng thêm tối đa một batch JDBC nữa). */
    private long preparedStatementsForAuthorChunk(int n, String prefix) {
        Statistics st = stats();
        st.clear();
        TenantContext.runAs(tenant, () -> authors.commitChunk(ctx(), authorRows(n, prefix)));
        assertThat(st.getEntityInsertCount()).isEqualTo(n);
        return st.getPrepareStatementCount();
    }

    @Test
    void ghiChunkTacGiaDungTenantVaSoCauSqlKhongTangTheoSoDong() throws Exception {
        long small = preparedStatementsForAuthorChunk(10, "S-");
        long large = preparedStatementsForAuthorChunk(200, "L-");

        // N+1 hay merge (SELECT trước mỗi INSERT) sẽ thêm ~190 câu; batch chỉ thêm tối đa vài lần chuẩn bị
        assertThat(large - small).isLessThanOrEqualTo(3);
        assertThat(count("select count(*) from authors where tenant_id = '" + tenant + "' and name like 'S-%'")).isEqualTo(10);
        assertThat(count("select count(*) from authors where tenant_id = '" + tenant + "' and name like 'L-%'")).isEqualTo(200);
        assertThat(count("select count(*) from authors where name like 'L-%' and tenant_id <> '" + tenant + "'")).isZero();
    }

    @Test
    void uploadKiemTrungDbBangMotTruyVanVaConfirmBoQuaDongTrung() throws Exception {
        TenantContext.runAs(tenant, () -> authors.commitChunk(ctx(), authorRows(1, "Có sẵn-")));
        byte[] file = lms.catalog.catalog.unit.CatalogImportRows.single(CatalogImportSchemas.authors(), List.of(
                row("name", "Có sẵn-0"), row("name", "Mới 1"), row("name", "Mới 2"), row("name", "Mới 2")));
        Statistics st = stats();
        st.clear();

        ImportViews.SessionView s = TenantContext.callAs(tenant, () -> workflow.upload(authors, actor, "a.xlsx", file));

        assertThat(s.totals().valid()).isEqualTo(2);
        assertThat(s.totals().duplicate()).isEqualTo(1);
        assertThat(s.totals().invalid()).isEqualTo(1);
        assertThat(st.getQueryExecutionCount()).isEqualTo(1);   // một truy vấn IN cho cả file
        assertThat(st.getEntityInsertCount()).isZero();         // preview không ghi DB

        ImportViews.SessionView done = confirmAndWait(authors, s.importId());
        assertThat(done.status()).isEqualTo(ImportSessionStatus.COMMITTED);
        assertThat(done.result().created()).isEqualTo(2);
        assertThat(count("select count(*) from authors where tenant_id = '" + tenant + "' and name like 'Mới %'")).isEqualTo(2);
    }

    @Test
    void theLoaiChaTruocConGhiDungCayTrongDbThat() throws Exception {
        byte[] file = lms.catalog.catalog.unit.CatalogImportRows.single(CatalogImportSchemas.categories(), List.of(
                row("name", "Con IT", "parentName", "Cha IT"), row("name", "Cha IT")));
        ImportViews.SessionView s = TenantContext.callAs(tenant, () -> workflow.upload(categories, actor, "c.xlsx", file));
        assertThat(s.totals().valid()).isEqualTo(2);

        ImportViews.SessionView done = confirmAndWait(categories, s.importId());

        assertThat(done.result().created()).isEqualTo(2);
        assertThat(count("select count(*) from categories c join categories p on p.id = c.parent_id "
                + "where c.tenant_id = '" + tenant + "' and c.name = 'Con IT' and p.name = 'Cha IT'")).isEqualTo(1);
    }

    @Test
    void dauSachVaBanSaoGhiTrongMotChunkDungTenantVaSoCauSqlKhongTangTheoSoDong() throws Exception {
        long small = preparedStatementsForTitleChunk(5, "T5-");
        long large = preparedStatementsForTitleChunk(80, "T80-");   // 80 đầu sách + 80 bản sao = 160 dòng

        assertThat(large - small).isLessThanOrEqualTo(6);
        assertThat(count("select count(*) from titles where tenant_id = '" + tenant + "' and isbn like 'T80-%'")).isEqualTo(80);
        assertThat(count("select count(*) from book_copies where tenant_id = '" + tenant + "' and barcode like 'BT80-%'"))
                .isEqualTo(80);
        assertThat(count("select count(*) from book_copies bc join titles t on t.id = bc.title_id "
                + "where bc.barcode like 'BT80-%' and t.isbn = 'T80-0' and t.tenant_id = bc.tenant_id")).isEqualTo(1);
    }

    private long preparedStatementsForTitleChunk(int n, String prefix) {
        List<ImportRow> chunk = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            chunk.add(ImportRow.of("DauSach", i + 2, Map.of("isbn", prefix + i, "name", "S" + i, "author", "A",
                    "coverPrice", "1000", "tags", "x;y")));
            chunk.add(ImportRow.of("BanSao", i + 2, Map.of("isbn", prefix + i, "barcode", "B" + prefix + i,
                    "acquiredDate", "2024-01-15", "acquisitionSource", "PURCHASE", "purchasePrice", "5000")));
        }
        Statistics st = stats();
        st.clear();
        var result = TenantContext.callAs(tenant, () -> titles.commitChunk(ctx(), chunk));
        assertThat(result.outcomes()).hasSize(2 * n);
        assertThat(result.outcomes()).allMatch(o -> o.status() == lms.imports.core.OutcomeStatus.CREATED);
        assertThat(st.getEntityInsertCount()).isEqualTo(2L * n);
        return st.getPrepareStatementCount();
    }

    @Test
    void trungIsbnGhiDongThoiTrongDbThatThanhBoQuaKhongHongCaChunk() throws Exception {
        List<ImportRow> first = List.of(ImportRow.of("DauSach", 2, Map.of("isbn", "RACE-1", "name", "A", "author", "x", "coverPrice", "1")));
        TenantContext.runAs(tenant, () -> titles.commitChunk(ctx(), first));
        // cùng chunk có đầu sách trùng (người khác vừa tạo) và đầu sách mới
        List<ImportRow> chunk = List.of(
                ImportRow.of("DauSach", 2, Map.of("isbn", "RACE-1", "name", "A", "author", "x", "coverPrice", "1")),
                ImportRow.of("DauSach", 3, Map.of("isbn", "RACE-2", "name", "B", "author", "x", "coverPrice", "1")));

        var result = TenantContext.callAs(tenant, () -> titles.commitChunk(ctx(), chunk));

        assertThat(result.outcomes()).extracting(o -> o.status()).containsExactly(
                lms.imports.core.OutcomeStatus.SKIPPED_DUPLICATE, lms.imports.core.OutcomeStatus.CREATED);
        assertThat(count("select count(*) from titles where tenant_id = '" + tenant + "' and isbn like 'RACE-%'")).isEqualTo(2);
    }

    @Test
    void viTriKeNhapBangVaiTroUngDungVoiRls() throws Exception {
        byte[] file = lms.catalog.catalog.unit.CatalogImportRows.single(CatalogImportSchemas.shelfLocations(), List.of(
                row("code", "IT-A1", "floor", "2", "capacity", 50)));
        ImportViews.SessionView s = TenantContext.callAs(tenant, () -> workflow.upload(shelves, actor, "k.xlsx", file));
        ImportViews.SessionView done = confirmAndWait(shelves, s.importId());
        assertThat(done.result().created()).isEqualTo(1);
        assertThat(count("select count(*) from shelf_locations where tenant_id = '" + tenant + "' and capacity = 50")).isEqualTo(1);
    }
}
