package lms.identity.apiclient.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.identity.apiclient.application.ApplicationRevocationPort;
import lms.identity.apiclient.application.ApplicationUsagePort;
import lms.identity.apiclient.application.ApplicationViews.ApplicationDetail;
import lms.identity.apiclient.application.CreateApplicationUseCase.Command;
import lms.identity.apiclient.application.ManageApplicationUseCase;
import lms.identity.apiclient.application.ManageCredentialsUseCase;
import lms.identity.apiclient.application.QueryApplicationUsageUseCase;
import lms.identity.apiclient.application.QueryApplicationUsageUseCase.UsageReport;
import lms.identity.apiclient.domain.ApplicationException;
import lms.identity.apiclient.domain.ApplicationException.Reason;
import lms.identity.tenant.application.ManageApplicationOriginsUseCase;
import lms.identity.tenant.application.StorefrontOriginPolicy;
import org.junit.jupiter.api.Test;

/**
 * Thống kê sử dụng (đúng tenant, IDOR, giới hạn 31 ngày, lỗi kho) và ghi dấu thu hồi token tức thời khi tạm ngưng, xóa,
 * thu hồi credential COMPROMISED (không ghi khi xoay vòng thường hay kích hoạt lại).
 */
class ApplicationUsageAndRevocationTest {

    private final ApiClientFixture f = new ApiClientFixture();
    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();

    /** Kho bộ đếm giả: ghi lại lời gọi, trả số theo ngày. */
    static final class FakeUsage implements ApplicationUsagePort {
        final List<List<Object>> calls = new ArrayList<>();
        Map<LocalDate, Long> data = Map.of();
        RuntimeException fail;

        @Override
        public Map<LocalDate, Long> dailyRequests(UUID tenantId, UUID applicationId, List<LocalDate> days) {
            calls.add(List.of(tenantId, applicationId, days.size()));
            if (fail != null) throw fail;
            return data;
        }
    }

    private final FakeUsage port = new FakeUsage();
    private final QueryApplicationUsageUseCase usage =
            new QueryApplicationUsageUseCase(f.applications, port, f.clock);

    private ApplicationDetail integration(UUID tenant) {
        return f.create.execute(tenant, new Command("ERP", "INTEGRATION", 600), admin);
    }

    // ------------------------------------------------------------------ thống kê

    @Test
    void traTongTheoNgayDienSo0ChoNgayKhongCoDuLieu() {
        ApplicationDetail app = integration(tenantA);
        port.data = Map.of(LocalDate.parse("2026-11-16"), 10L, LocalDate.parse("2026-11-18"), 5L);

        UsageReport report = usage.usage(tenantA, app.id(), LocalDate.parse("2026-11-16"), LocalDate.parse("2026-11-18"));

        assertThat(report.days()).extracting(d -> d.date().toString(), d -> d.totalRequests())
                .containsExactly(org.assertj.core.groups.Tuple.tuple("2026-11-16", 10L),
                        org.assertj.core.groups.Tuple.tuple("2026-11-17", 0L),
                        org.assertj.core.groups.Tuple.tuple("2026-11-18", 5L));
        assertThat(report.totalRequests()).isEqualTo(15);
        assertThat(report.applicationId()).isEqualTo(app.id());
    }

    @Test
    void thieuThamSoLay30NgayKetThucHomNayUtc() {
        ApplicationDetail app = integration(tenantA);

        UsageReport report = usage.usage(tenantA, app.id(), null, null);

        assertThat(report.to()).isEqualTo(LocalDate.parse("2026-11-18"));
        assertThat(report.from()).isEqualTo(LocalDate.parse("2026-10-20"));
        assertThat(report.days()).hasSize(30);
    }

    @Test
    void tenantKhacLa404CungThanVoiKhongTonTaiVaKhongDocBoDem() {
        ApplicationDetail app = integration(tenantA);

        assertThatThrownBy(() -> usage.usage(tenantB, app.id(), null, null))
                .isInstanceOfSatisfying(ApplicationException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.APPLICATION_NOT_FOUND);
                    ApplicationException missing = catchApplication(() -> usage.usage(tenantB, UUID.randomUUID(), null, null));
                    assertThat(e.getMessage()).isEqualTo(missing.getMessage());
                    assertThat(missing.reason()).isEqualTo(e.reason());
                });
        assertThat(port.calls).isEmpty();
    }

    @Test
    void toiDa31NgayBaoGomHaiDau() {
        ApplicationDetail app = integration(tenantA);
        LocalDate to = LocalDate.parse("2026-11-18");

        assertThat(usage.usage(tenantA, app.id(), to.minusDays(30), to).days()).hasSize(31);
        assertThatThrownBy(() -> usage.usage(tenantA, app.id(), to.minusDays(31), to))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.INVALID_APPLICATION));
    }

    @Test
    void fromSauToLa400() {
        ApplicationDetail app = integration(tenantA);
        assertThatThrownBy(() -> usage.usage(tenantA, app.id(), LocalDate.parse("2026-11-18"),
                LocalDate.parse("2026-11-17")))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.INVALID_APPLICATION));
        assertThat(port.calls).isEmpty();
    }

    @Test
    void khoBoDemLoiThiLoiRoKhongTraSoGia() {
        ApplicationDetail app = integration(tenantA);
        port.fail = new ApplicationException(Reason.USAGE_UNAVAILABLE, "Thống kê tạm thời không khả dụng");

        assertThatThrownBy(() -> usage.usage(tenantA, app.id(), null, null))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.USAGE_UNAVAILABLE));
    }

    private static ApplicationException catchApplication(Runnable r) {
        try {
            r.run();
        } catch (ApplicationException e) {
            return e;
        }
        throw new AssertionError("không ném ApplicationException");
    }

    // ------------------------------------------------------------------ thu hồi tức thời

    private record Mark(UUID applicationId, Instant at) {
    }

    private final List<Mark> marks = new ArrayList<>();
    private final ApplicationRevocationPort revocation = (id, at) -> marks.add(new Mark(id, at));

    private ManageApplicationUseCase manageWithRevocation() {
        ManageApplicationOriginsUseCase origins = new ManageApplicationOriginsUseCase(f.tenants, f.originRows,
                f.publisher, f.tx, StorefrontOriginPolicy.defaults());
        return new ManageApplicationUseCase(f.applications, f.credentials, f.history, origins, f.publisher, f.tx,
                f.random, f.clock, revocation);
    }

    private ManageCredentialsUseCase credsWithRevocation() {
        return new ManageCredentialsUseCase(f.applications, f.credentials, f.history, f.publisher, f.tx, f.policy,
                f.random, f.clock, revocation);
    }

    @Test
    void tamNgungGhiDauThuHoiKichHoatLaiThiKhong() {
        ApplicationDetail app = integration(tenantA);
        ManageApplicationUseCase manage = manageWithRevocation();

        manage.suspend(tenantA, app.id(), admin);
        assertThat(marks).singleElement().satisfies(m -> {
            assertThat(m.applicationId()).isEqualTo(app.id());
            assertThat(m.at()).isEqualTo(ApiClientFixture.START.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        });

        manage.reactivate(tenantA, app.id(), admin);
        assertThat(marks).hasSize(1);
    }

    @Test
    void xoaApplicationGhiDauThuHoi() {
        ApplicationDetail app = integration(tenantA);
        manageWithRevocation().delete(tenantA, app.id(), admin);
        assertThat(marks).singleElement().satisfies(m -> assertThat(m.applicationId()).isEqualTo(app.id()));
    }

    @Test
    void chiThuHoiCredentialCompromisedMoiGhiDau() {
        ApplicationDetail app = integration(tenantA);
        ManageCredentialsUseCase creds = credsWithRevocation();
        UUID c1 = creds.create(tenantA, app.id(), null, admin).credentialId();
        UUID c2 = creds.create(tenantA, app.id(), null, admin).credentialId();

        creds.revoke(tenantA, app.id(), c1, "ROTATED", admin);
        assertThat(marks).isEmpty();

        creds.revoke(tenantA, app.id(), c2, "COMPROMISED", admin);
        assertThat(marks).singleElement().satisfies(m -> assertThat(m.applicationId()).isEqualTo(app.id()));
    }

    @Test
    void thaoTacLoiKhongGhiDau() {
        ManageApplicationUseCase manage = manageWithRevocation();
        assertThatThrownBy(() -> manage.suspend(tenantB, UUID.randomUUID(), admin))
                .isInstanceOf(ApplicationException.class);
        assertThat(marks).isEmpty();
    }

    @Test
    void constructorCuKhongCoThuHoiVanChayBinhThuong() {
        ApplicationDetail app = integration(tenantA);
        assertThat(f.manage.suspend(tenantA, app.id(), admin).status()).isEqualTo("SUSPENDED");
    }
}
