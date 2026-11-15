package lms.support.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import lms.support.notification.application.CountUnreadNotificationsUseCase;
import lms.support.notification.application.CreateNotificationCommand;
import lms.support.notification.application.CreateNotificationUseCase;
import lms.support.notification.application.ListMyNotificationsUseCase;
import lms.support.notification.application.MarkAllNotificationsReadUseCase;
import lms.support.notification.application.MarkNotificationReadUseCase;
import lms.support.notification.domain.NotificationNotFoundException;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MarkNotificationsUseCasesTest {

    private final Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    private final Clock createClock = Clock.fixed(t0, ZoneOffset.UTC);
    private final Clock readClock = Clock.fixed(t0.plusSeconds(60), ZoneOffset.UTC);
    private final Clock laterClock = Clock.fixed(t0.plusSeconds(120), ZoneOffset.UTC);
    private final InMemoryNotifications store = new InMemoryNotifications();
    private final CreateNotificationUseCase create = new CreateNotificationUseCase(store, createClock);
    private final ListMyNotificationsUseCase list = new ListMyNotificationsUseCase(store);
    private final MarkNotificationReadUseCase markRead = new MarkNotificationReadUseCase(store, readClock);
    private final MarkAllNotificationsReadUseCase markAll = new MarkAllNotificationsReadUseCase(store, readClock);
    private final CountUnreadNotificationsUseCase count = new CountUnreadNotificationsUseCase(store);
    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();
    private final UUID me = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private UUID seed(UUID user) {
        create.execute(new CreateNotificationCommand(UUID.randomUUID(), user, "FINE_CREATED", "msg"));
        return list.execute(user).get(0).id();
    }

    @Test
    void danhDauMotThongBaoVaDemChuaDoc() {
        TenantContext.runAs(tenantA, () -> {
            UUID id = seed(me);
            seed(me);
            assertThat(count.execute(me)).isEqualTo(2);
            var res = markRead.execute(id, me);
            assertThat(res.readAt()).isEqualTo(readClock.instant());
            assertThat(count.execute(me)).isEqualTo(1);
        });
    }

    @Test
    void danhDauLaiKhongDoiThoiDiemDoc() {
        TenantContext.runAs(tenantA, () -> {
            UUID id = seed(me);
            markRead.execute(id, me);
            var again = new MarkNotificationReadUseCase(store, laterClock).execute(id, me);
            assertThat(again.readAt()).isEqualTo(readClock.instant());
            assertThat(count.execute(me)).isZero();
        });
    }

    @Test
    void thongBaoCuaNguoiKhacLa404VaKhongBiDoi() {
        TenantContext.runAs(tenantA, () -> {
            UUID id = seed(other);
            assertThatThrownBy(() -> markRead.execute(id, me)).isInstanceOf(NotificationNotFoundException.class);
            assertThat(count.execute(other)).isEqualTo(1);
        });
    }

    @Test
    void khongTonTaiLa404() {
        TenantContext.runAs(tenantA, () -> assertThatThrownBy(() -> markRead.execute(UUID.randomUUID(), me))
                .isInstanceOf(NotificationNotFoundException.class));
    }

    @Test
    void thongBaoCuaTenantKhacLa404() {
        UUID[] id = new UUID[1];
        TenantContext.runAs(tenantA, () -> id[0] = seed(me));
        TenantContext.runAs(tenantB, () -> assertThatThrownBy(() -> markRead.execute(id[0], me))
                .isInstanceOf(NotificationNotFoundException.class));
    }

    @Test
    void docTatCaChiAnhHuongCuaMinhTrongTenantHienTai() {
        TenantContext.runAs(tenantA, () -> {
            seed(me);
            seed(me);
            seed(other);
            assertThat(markAll.execute(me)).isEqualTo(2);
            assertThat(count.execute(me)).isZero();
            assertThat(count.execute(other)).isEqualTo(1);
            assertThat(markAll.execute(me)).isZero();
        });
    }

    @Test
    void demChuaDocCachLyTheoTenant() {
        TenantContext.runAs(tenantA, () -> seed(me));
        TenantContext.runAs(tenantB, () -> assertThat(count.execute(me)).isZero());
    }
}
