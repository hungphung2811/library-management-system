package lms.support.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.UUID;
import lms.support.notification.application.CreateNotificationUseCase;
import lms.support.notification.application.ListMyNotificationsUseCase;
import lms.support.notification.infrastructure.messaging.ContractExpiringConsumer;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** T4 (ADR-0012): một thông báo mỗi người nhận, giao lại không nhân bản, thiếu tenant bị bỏ qua, cách ly theo tenant. */
class ContractExpiringConsumerTest {

    private final InMemoryNotifications store = new InMemoryNotifications();
    private final ContractExpiringConsumer consumer = new ContractExpiringConsumer(new ObjectMapper(),
            new CreateNotificationUseCase(store, Clock.systemUTC()));
    private final ListMyNotificationsUseCase list = new ListMyNotificationsUseCase(store);
    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();
    private final UUID admin1 = UUID.randomUUID();
    private final UUID admin2 = UUID.randomUUID();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private String message(String eventType, UUID eventId, UUID tenantId, String recipientsJson) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + eventType + "\",\"aggregateId\":\""
                + UUID.randomUUID() + "\",\"occurredAt\":\"2026-11-17T00:00:00Z\",\"version\":1,"
                + (tenantId == null ? "" : "\"tenantId\":\"" + tenantId + "\",")
                + "\"payload\":{\"eventId\":\"" + eventId + "\",\"contractId\":\"" + UUID.randomUUID()
                + "\",\"contractName\":\"Gói A\",\"endDate\":\"2026-12-31\",\"daysBefore\":30,"
                + "\"recipientUserIds\":" + recipientsJson + "}}";
    }

    private String recipients() {
        return "[\"" + admin1 + "\",\"" + admin2 + "\"]";
    }

    @Test
    void motThongBaoChoMoiNguoiNhanKhongChuaTienVaGiaoLaiKhongNhanBan() {
        UUID eventId = UUID.randomUUID();
        String msg = message("TenantContractExpiringSoon", eventId, tenantA, recipients());
        consumer.onEvent(msg);
        consumer.onEvent(msg);

        TenantContext.runAs(tenantA, () -> {
            for (UUID admin : new UUID[] {admin1, admin2}) {
                var mine = list.execute(admin);
                assertThat(mine).hasSize(1);
                assertThat(mine.get(0).type()).isEqualTo("CONTRACT_EXPIRING");
                assertThat(mine.get(0).message()).contains("31/12/2026", "Gói A", "30 ngày");
                assertThat(mine.get(0).message()).doesNotContain("1000000");
            }
        });
        TenantContext.runAs(tenantB, () -> assertThat(list.execute(admin1)).isEmpty());
    }

    @Test
    void thieuTenantEventKhacHoacHongBiBoQua() {
        consumer.onEvent(message("TenantContractExpiringSoon", UUID.randomUUID(), null, recipients()));
        consumer.onEvent(message("TenantCreated", UUID.randomUUID(), tenantA, recipients()));
        consumer.onEvent(message("TenantContractExpiringSoon", UUID.randomUUID(), tenantA, "[\"khong-phai-uuid\"]"));
        consumer.onEvent("không phải json");
        TenantContext.runAs(tenantA, () -> {
            assertThat(list.execute(admin1)).isEmpty();
            assertThat(list.execute(admin2)).isEmpty();
        });
    }
}
