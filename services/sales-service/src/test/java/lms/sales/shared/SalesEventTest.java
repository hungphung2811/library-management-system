package lms.sales.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lms.messaging.EventEnvelope;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.infrastructure.messaging.SalesEvent;
import org.junit.jupiter.api.Test;

class SalesEventTest {

    private static SalesEvent of(String action) {
        UUID aggregate = UUID.randomUUID();
        return SalesEvent.of(UUID.randomUUID(), new SalesAuditPort.AuditEntry(UUID.randomUUID(), action, "SalesOrder",
                aggregate, UUID.randomUUID(), Instant.parse("2026-11-17T03:00:00Z"), Map.of("grandTotal", 1000L)));
    }

    @Test
    void donPosTaoRaLaOrderCompleted() {
        assertThat(of(SalesAuditPort.Actions.ORDER_CREATED).eventType()).isEqualTo("OrderCompleted");
    }

    @Test
    void cacHanhDongKhacDoiSangPascalCase() {
        assertThat(of(SalesAuditPort.Actions.GOODS_RECEIPT_CONFIRMED).eventType()).isEqualTo("GoodsReceiptConfirmed");
        assertThat(of(SalesAuditPort.Actions.STOCK_ADJUSTED).eventType()).isEqualTo("StockAdjusted");
        assertThat(of(SalesAuditPort.Actions.SETTINGS_CHANGED).eventType()).isEqualTo("SettingsChanged");
    }

    @Test
    void envelopeMangDuLieuTomTatKhongCoTruongCaNhan() {
        SalesEvent e = of(SalesAuditPort.Actions.ORDER_CREATED);
        EventEnvelope env = EventEnvelope.of(e, UUID.randomUUID());
        assertThat(env.eventType()).isEqualTo("OrderCompleted");
        assertThat(env.eventId()).isEqualTo(e.eventId());
        assertThat(env.aggregateId()).isEqualTo(e.aggregateId());
        assertThat(env.tenantId()).isNotNull();
        assertThat(e.data()).containsEntry("grandTotal", 1000L);
        assertThat(e.version()).isEqualTo(1);
    }
}
