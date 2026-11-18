package lms.sales.shared.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lms.sales.shared.application.SalesAuditPort.Actions;
import lms.sales.shared.application.SalesAuditPort.AuditEntry;
import lms.sales.shared.application.SalesEventEnrichmentPort;
import lms.sales.shared.infrastructure.messaging.SalesEvent;
import lms.sales.shared.infrastructure.messaging.SalesEventPublisher;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class SalesAuditAdapterEnrichmentTest {

    private final UUID customer = UUID.randomUUID();
    private EntityManager em;
    private SalesEventPublisher publisher;

    @BeforeEach
    void setUp() {
        em = mock(EntityManager.class);
        publisher = mock(SalesEventPublisher.class);
        TenantContext.set(UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private SalesAuditAdapter adapter(SalesEventEnrichmentPort port) {
        SalesAuditAdapter a = new SalesAuditAdapter(publisher, port);
        ReflectionTestUtils.setField(a, "em", em);
        return a;
    }

    private AuditEntry entry(Map<String, Object> data) {
        return new AuditEntry(UUID.randomUUID(), Actions.ORDER_PAID, "SalesOrder", UUID.randomUUID(),
                UUID.randomUUID(), Instant.parse("2026-11-19T03:00:00Z"), data);
    }

    @Test
    void truongLamGiauChiVaoSuKienConDongAuditVaTruongCuGiuNguyen() {
        AuditEntry e = entry(Map.of("orderNo", "SO-1", "status", "PAID", "amount", 100L));
        adapter(x -> Map.of("customerUserId", customer.toString(), "channel", "ONLINE",
                "orderNo", "GHI-DE-KHONG-DUOC")).record(e);

        ArgumentCaptor<SalesAuditJpaEntity> row = ArgumentCaptor.forClass(SalesAuditJpaEntity.class);
        verify(em).persist(row.capture());
        assertThat(row.getValue().getData()).isEqualTo(e.data());
        assertThat(row.getValue().getAction()).isEqualTo(Actions.ORDER_PAID);

        ArgumentCaptor<SalesEvent> ev = ArgumentCaptor.forClass(SalesEvent.class);
        verify(publisher).publishAfterCommit(ev.capture());
        SalesEvent event = ev.getValue();
        assertThat(event.eventType()).isEqualTo("OrderPaid");
        assertThat(event.eventId()).isEqualTo(row.getValue().getId());
        assertThat(event.aggregateId()).isEqualTo(e.aggregateId().toString());
        assertThat(event.data()).containsEntry("orderNo", "SO-1").containsEntry("status", "PAID")
                .containsEntry("amount", 100L).containsEntry("customerUserId", customer.toString())
                .containsEntry("channel", "ONLINE");
        assertThat(event.version()).isEqualTo(1);
    }

    @Test
    void khongLamGiauThiSuKienGiongHetCu() {
        AuditEntry e = entry(Map.of("orderNo", "SO-1"));
        adapter(SalesEventEnrichmentPort.NONE).record(e);
        ArgumentCaptor<SalesEvent> ev = ArgumentCaptor.forClass(SalesEvent.class);
        verify(publisher).publishAfterCommit(ev.capture());
        assertThat(ev.getValue().data()).isEqualTo(e.data());
    }

    @Test
    void goiPortDungMotLanMoiDongAudit() {
        SalesEventEnrichmentPort port = mock(SalesEventEnrichmentPort.class);
        when(port.enrich(any())).thenReturn(Map.of());
        AuditEntry e = entry(Map.of());
        adapter(port).record(e);
        verify(port).enrich(e);
    }

    @Test
    void kieuSuKienDanXuatTuDuLieuGocNenOrderCreatedOnlineVanLaOrderPlaced() {
        AuditEntry e = new AuditEntry(UUID.randomUUID(), Actions.ORDER_CREATED, "SalesOrder", UUID.randomUUID(),
                UUID.randomUUID(), Instant.now(), Map.of("channel", "ONLINE"));
        adapter(x -> Map.of("channel", "POS", "customerUserId", customer.toString())).record(e);
        ArgumentCaptor<SalesEvent> ev = ArgumentCaptor.forClass(SalesEvent.class);
        verify(publisher).publishAfterCommit(ev.capture());
        assertThat(ev.getValue().eventType()).isEqualTo("OrderPlaced");
        assertThat(ev.getValue().data()).containsEntry("channel", "ONLINE");
    }
}
