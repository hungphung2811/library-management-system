package lms.support.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import lms.support.notification.application.ListMyNotificationsUseCase;
import lms.support.notification.infrastructure.messaging.ReservationReadyConsumer;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ReservationReadyConsumerTest {

    private final NotificationKit kit = new NotificationKit();
    private final ReservationReadyConsumer consumer = new ReservationReadyConsumer(new ObjectMapper(), kit.enqueue);
    private final ListMyNotificationsUseCase list = new ListMyNotificationsUseCase(kit.inApp);
    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();
    private final UUID reader = UUID.randomUUID();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private String message(String eventType, UUID eventId, UUID tenantId, String readerId) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + eventType + "\",\"aggregateId\":\""
                + UUID.randomUUID() + "\",\"occurredAt\":\"2026-01-01T00:00:00Z\",\"version\":1,"
                + (tenantId == null ? "" : "\"tenantId\":\"" + tenantId + "\",")
                + "\"payload\":{\"eventId\":\"" + eventId + "\",\"readerId\":\"" + readerId + "\",\"titleId\":\""
                + UUID.randomUUID() + "\",\"copyId\":\"" + UUID.randomUUID() + "\",\"holdUntil\":\"2026-01-04\"}}";
    }

    @Test
    void eventCuaTenantATaoDungMotThongBaoChoDocGiaVaKhongHienOTenantB() {
        UUID eventId = UUID.randomUUID();
        String msg = message("ReservationReadyEvent", eventId, tenantA, reader.toString());
        consumer.onEvent(msg);
        consumer.onEvent(msg); // giao lại: idempotent theo eventId

        TenantContext.runAs(tenantA, () -> {
            var mine = list.execute(reader);
            assertThat(mine).hasSize(1);
            assertThat(mine.get(0).type()).isEqualTo("RESERVATION_READY");
            assertThat(mine.get(0).message()).contains("2026-01-04");
        });
        TenantContext.runAs(tenantB, () -> assertThat(list.execute(reader)).isEmpty());
    }

    @Test
    void thieuTenantThiBoQua() {
        consumer.onEvent(message("ReservationReadyEvent", UUID.randomUUID(), null, reader.toString()));
        TenantContext.runAs(tenantA, () -> assertThat(list.execute(reader)).isEmpty());
    }

    @Test
    void eventKhacHoacHongBiBoQuaKhongNemLoi() {
        consumer.onEvent(message("CopyReleasedEvent", UUID.randomUUID(), tenantA, reader.toString()));
        consumer.onEvent(message("ReservationReadyEvent", UUID.randomUUID(), tenantA, "khong-phai-uuid"));
        consumer.onEvent("không phải json");
        TenantContext.runAs(tenantA, () -> assertThat(list.execute(reader)).isEmpty());
    }
}
