package lms.support.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import lms.support.notification.application.CreateNotificationCommand;
import lms.support.notification.application.CreateNotificationUseCase;
import lms.support.notification.application.ListMyNotificationsUseCase;
import lms.support.notification.domain.InvalidNotificationException;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class NotificationUseCasesTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final InMemoryNotifications store = new InMemoryNotifications();
    private final CreateNotificationUseCase create = new CreateNotificationUseCase(store, clock);
    private final ListMyNotificationsUseCase list = new ListMyNotificationsUseCase(store);
    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private CreateNotificationCommand command(UUID eventId, UUID userId) {
        return new CreateNotificationCommand(eventId, userId, "RESERVATION_READY", "Sách đã sẵn sàng");
    }

    @Test
    void taoThongBaoVaLietKeCuaChinhMinh() {
        UUID eventId = UUID.randomUUID();
        TenantContext.runAs(tenantA, () -> {
            assertThat(create.execute(command(eventId, user))).isTrue();
            var mine = list.execute(user);
            assertThat(mine).hasSize(1);
            assertThat(mine.get(0).type()).isEqualTo("RESERVATION_READY");
            assertThat(mine.get(0).createdAt()).isEqualTo(clock.instant());
            assertThat(mine.get(0).readAt()).isNull();
            assertThat(list.execute(UUID.randomUUID())).isEmpty();
        });
    }

    @Test
    void cungEventIdChiTaoMotThongBao() {
        UUID eventId = UUID.randomUUID();
        TenantContext.runAs(tenantA, () -> {
            assertThat(create.execute(command(eventId, user))).isTrue();
            assertThat(create.execute(command(eventId, user))).isFalse();
            assertThat(list.execute(user)).hasSize(1);
        });
    }

    @Test
    void thongBaoCuaTenantAKhongHienOTenantB() {
        TenantContext.runAs(tenantA, () -> create.execute(command(UUID.randomUUID(), user)));
        TenantContext.runAs(tenantB, () -> assertThat(list.execute(user)).isEmpty());
    }

    @Test
    void khongCoTenantThiTuChoi() {
        assertThatThrownBy(() -> create.execute(command(UUID.randomUUID(), user))).isInstanceOf(RuntimeException.class);
    }

    @Test
    void duLieuKhongHopLeBiTuChoi() {
        TenantContext.runAs(tenantA, () -> assertThatThrownBy(
                () -> create.execute(new CreateNotificationCommand(UUID.randomUUID(), user, " ", "x")))
                .isInstanceOf(InvalidNotificationException.class));
    }
}
