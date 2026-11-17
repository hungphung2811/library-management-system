package lms.circulation.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import lms.circulation.reporting.application.GetLendingSummaryUseCase;
import lms.circulation.reporting.application.LendingSummaryResponse;
import lms.demo.plan.DemoPlan;
import lms.demo.runner.DemoSeedProperties;
import lms.demo.runner.DemoSeedRunner;
import lms.demo.runner.DemoSeedStep;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Seed demo của circulation trên PostgreSQL thật, vai trò circulation_app (NOBYPASSRLS), profile {@code demo-seed}
 * (không web, Kafka listener tắt, job hết hạn đặt trước tắt, đúng như tiến trình seed thật). Cần Docker. Chạy:
 * {@code mvn -pl services/circulation-service -am test -Dtest=CirculationDemoSeedIT -DargLine=-Dapi.version=1.44}.
 * Runner thật gọi System.exit nên bị thay bằng mock; test tự dựng một {@link DemoSeedRunner} cùng các bước thật
 * và bắt mã thoát (guard + TenantContext + RLS được chạy thật).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.profiles.active=it,demo-seed",
        "app.security.enabled=false",
        "spring.kafka.bootstrap-servers=localhost:1",
        "app.clients.catalog.base-url=http://localhost:1",
        "app.clients.identity.base-url=http://localhost:1"})
@Testcontainers
class CirculationDemoSeedIT {

    static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    static final UUID OTHER_TENANT = UUID.randomUUID();
    static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    @MockitoBean DemoSeedRunner realRunner;

    @Autowired List<DemoSeedStep> steps;
    @Autowired ApplicationContext applicationContext;
    @Autowired GetLendingSummaryUseCase summary;

    private int runSeed(String password, List<String> profiles) {
        DemoSeedProperties props = new DemoSeedProperties(true, TENANT.toString(), password, false, DemoPlan.DEFAULT_SEED);
        return new DemoSeedRunner(props, profiles, steps, Clock.system(VN), applicationContext, code -> { })
                .execute();
    }

    private long count(String sql) throws Exception {
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void seedGhiDungDuLieuIdempotentVaThongKeDungBatBien() throws Exception {
        assertThat(steps).extracting(DemoSeedStep::name)
                .containsExactly("circulation.borrowings", "circulation.fines", "circulation.reservations");

        assertThat(runSeed("x", List.of("dev", "demo-seed"))).isEqualTo(DemoSeedRunner.EXIT_OK);

        String t = "tenant_id = '" + TENANT + "'";
        long borrowings = count("SELECT count(*) FROM borrowings WHERE " + t);
        long items = count("SELECT count(*) FROM borrowing_items WHERE " + t);
        long fines = count("SELECT count(*) FROM fines WHERE " + t);
        long reservations = count("SELECT count(*) FROM reservations WHERE " + t);
        assertThat(borrowings).isGreaterThanOrEqualTo(120);
        assertThat(count("SELECT count(*) FROM borrowing_items WHERE " + t + " AND title_id IS NULL")).isZero();
        assertThat(count("SELECT count(*) FROM borrowing_items WHERE " + t + " AND return_date IS NULL"))
                .isEqualTo(DemoPlan.generate(LocalDate.now(VN)).copies().stream()
                        .filter(c -> c.status() == DemoPlan.CopyStatus.LOANED).count());
        assertThat(count("SELECT count(*) FROM fines WHERE " + t + " AND status = 'UNPAID'")).isEqualTo(DemoPlan.UNPAID_FINES);
        assertThat(count("SELECT count(*) FROM reservations WHERE " + t + " AND status = 'READY'")).isEqualTo(reservations);

        // Chạy lần hai: không lỗi, không nhân đôi
        assertThat(runSeed("x", List.of("dev", "demo-seed"))).isEqualTo(DemoSeedRunner.EXIT_OK);
        assertThat(count("SELECT count(*) FROM borrowings WHERE " + t)).isEqualTo(borrowings);
        assertThat(count("SELECT count(*) FROM borrowing_items WHERE " + t)).isEqualTo(items);
        assertThat(count("SELECT count(*) FROM fines WHERE " + t)).isEqualTo(fines);
        assertThat(count("SELECT count(*) FROM reservations WHERE " + t)).isEqualTo(reservations);

        // LendingStats (dashboard) trên dữ liệu seed: đúng 6 phiếu quá hạn và 4 phạt chưa thu (RLS: chỉ tenant demo)
        LendingSummaryResponse demo = TenantContext.callAs(TENANT, () -> summary.execute(null));
        assertThat(demo.lending().overdueBorrowings()).isEqualTo(DemoPlan.OVERDUE_BORROWINGS);
        assertThat(demo.fines().unpaidCount()).isEqualTo(DemoPlan.UNPAID_FINES);
        assertThat(demo.fines().unpaidAmount()).isEqualByComparingTo(String.valueOf(DemoPlan.UNPAID_FINES_TOTAL));
        assertThat(demo.lending().activeBorrowings()).isEqualTo(
                DemoPlan.generate(LocalDate.now(VN)).borrowings().stream().filter(DemoPlan.BorrowingPlan::isActive).count());
        assertThat(demo.lending().borrowedToday()).isGreaterThanOrEqualTo(1);

        // Tenant khác không thấy gì
        LendingSummaryResponse other = TenantContext.callAs(OTHER_TENANT, () -> summary.execute(null));
        assertThat(other.lending().activeBorrowings()).isZero();
        assertThat(other.fines().unpaidCount()).isZero();
    }

    @Test
    void profileProdBiTuChoiVaKhongGhiGi() throws Exception {
        long before = count("SELECT count(*) FROM borrowings");

        int code = runSeed("x", List.of("prod", "demo-seed"));

        assertThat(code).isEqualTo(DemoSeedRunner.EXIT_REFUSED);
        assertThat(count("SELECT count(*) FROM borrowings")).isEqualTo(before);
    }
}
