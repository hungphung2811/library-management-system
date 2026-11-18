package lms.sales.ordering.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lms.sales.shared.application.SalesAuditPort.Actions;
import lms.sales.shared.application.SalesAuditPort.AuditEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class OrderEventEnricherTest {

    private static final Instant NOW = Instant.parse("2026-11-19T03:00:00Z");
    private static final Set<String> ALLOWED = Set.of("customerUserId", "orderNo", "channel", "fulfilment",
            "grandTotal", "cancelKind", "issuedAt");

    private final UUID tenant = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID customer = UUID.randomUUID();
    private final UUID staff = UUID.randomUUID();
    private EntityManager em;
    private OrderEventEnricher enricher;

    @BeforeEach
    void setUp() {
        em = mock(EntityManager.class);
        enricher = new OrderEventEnricher();
        ReflectionTestUtils.setField(enricher, "em", em);
    }

    private void order(UUID customerUserId, String channel, String fulfilment, long grandTotal) {
        SalesOrderJpaEntity e = mock(SalesOrderJpaEntity.class);
        when(e.getCustomerUserId()).thenReturn(customerUserId);
        when(e.getOrderNo()).thenReturn("SO-0001");
        when(e.getChannel()).thenReturn(channel);
        when(e.getFulfilment()).thenReturn(fulfilment);
        when(e.getGrandTotal()).thenReturn(grandTotal);
        when(em.find(SalesOrderJpaEntity.class, orderId)).thenReturn(e);
    }

    private AuditEntry orderEntry(String action, UUID actor, Map<String, Object> data) {
        return new AuditEntry(tenant, action, "SalesOrder", orderId, actor, NOW, data);
    }

    @Test
    void donOnlineCoTaiKhoanCoDuTruongDinhDanh() {
        order(customer, "ONLINE", "PICKUP", 250_000L);
        Map<String, Object> x = enricher.enrich(
                orderEntry(Actions.ORDER_READY_FOR_PICKUP, staff, Map.of("orderNo", "SO-0001")));
        assertThat(x).containsEntry("customerUserId", customer.toString()).containsEntry("orderNo", "SO-0001")
                .containsEntry("channel", "ONLINE").containsEntry("fulfilment", "PICKUP")
                .containsEntry("grandTotal", 250_000L).doesNotContainKey("cancelKind");
    }

    @Test
    void khachLeHoacPosKhongTaiKhoanThiVangCustomerUserId() {
        order(null, "POS", "COUNTER", 90_000L);
        Map<String, Object> x = enricher.enrich(orderEntry(Actions.ORDER_CREATED, staff, Map.of()));
        assertThat(x).doesNotContainKey("customerUserId").containsEntry("channel", "POS")
                .containsEntry("grandTotal", 90_000L);
        assertThat(x.values()).doesNotContainNull();
    }

    @Test
    void khachTuHuyDonLaCustomer() {
        order(customer, "ONLINE", "DELIVERY", 1L);
        Map<String, Object> x = enricher.enrich(orderEntry(Actions.ORDER_CANCELLED, customer,
                Map.of("byCustomer", true, "stockEffect", "RELEASE")));
        assertThat(x).containsEntry("cancelKind", "CUSTOMER");
    }

    @Test
    void nhanVienHuyTayLaStaff() {
        order(customer, "ONLINE", "DELIVERY", 1L);
        Map<String, Object> x = enricher.enrich(orderEntry(Actions.ORDER_CANCELLED, staff,
                Map.of("byCustomer", false, "stockEffect", "RESTOCK")));
        assertThat(x).containsEntry("cancelKind", "STAFF");
    }

    @Test
    void jobHetHanGiuHangLaExpired() {
        order(customer, "ONLINE", "PICKUP", 1L);
        Map<String, Object> x = enricher.enrich(orderEntry(Actions.ORDER_CANCELLED, null,
                Map.of("byCustomer", false, "stockEffect", "RELEASE")));
        assertThat(x).containsEntry("cancelKind", "EXPIRED");
    }

    @Test
    void jobHetHanThanhToanLaPaymentTimeout() {
        order(customer, "ONLINE", "PICKUP", 1L);
        Map<String, Object> x = enricher.enrich(orderEntry(Actions.ORDER_CANCELLED, null,
                Map.of("paymentIntent", "EXPIRED", "stockEffect", "RELEASE")));
        assertThat(x).containsEntry("cancelKind", "PAYMENT_TIMEOUT");
    }

    @Test
    void khachHuyLenhThanhToanDangChoLaCustomerConThuNganHuyLaStaff() {
        order(customer, "ONLINE", "PICKUP", 1L);
        assertThat(enricher.enrich(orderEntry(Actions.ORDER_CANCELLED, customer, Map.of("paymentIntent", "CANCELLED"))))
                .containsEntry("cancelKind", "CUSTOMER");
        assertThat(enricher.enrich(orderEntry(Actions.ORDER_CANCELLED, staff, Map.of("paymentIntent", "CANCELLED"))))
                .containsEntry("cancelKind", "STAFF");
    }

    @Test
    void hoanTienLayDonQuaOrderIdVaKhongGhiDeAmount() {
        order(customer, "ONLINE", "DELIVERY", 500_000L);
        Map<String, Object> x = enricher.enrich(new AuditEntry(tenant, Actions.REFUND_COMPLETED, "Refund",
                UUID.randomUUID(), staff, NOW, Map.of("orderId", orderId.toString(), "amount", 120_000L)));
        assertThat(x).containsEntry("customerUserId", customer.toString()).containsEntry("orderNo", "SO-0001")
                .containsEntry("channel", "ONLINE").doesNotContainKeys("grandTotal", "cancelKind", "amount");
    }

    @Test
    void hoaDonPhatHanhCoIssuedAtLaThoiDiemPhatHanh() {
        order(customer, "ONLINE", "DELIVERY", 500_000L);
        Map<String, Object> x = enricher.enrich(new AuditEntry(tenant, Actions.INVOICE_ISSUED, "Invoice",
                UUID.randomUUID(), staff, NOW, Map.of("orderId", orderId.toString(), "invoiceNo", "C26TAA-0000001",
                "grandTotal", 500_000L)));
        assertThat(x).containsEntry("customerUserId", customer.toString()).containsEntry("orderNo", "SO-0001")
                .containsEntry("issuedAt", "2026-11-19T03:00:00Z");
        Map<String, Object> y = enricher.enrich(new AuditEntry(tenant, Actions.INVOICE_CANCELLED, "Invoice",
                UUID.randomUUID(), staff, NOW, Map.of("orderId", orderId.toString())));
        assertThat(y).doesNotContainKey("issuedAt");
    }

    @Test
    void loaiAggregateKhacHoacThieuOrderIdKhongDocDonNaoCa() {
        assertThat(enricher.enrich(new AuditEntry(tenant, Actions.STOCK_ADJUSTED, "StockItem", UUID.randomUUID(),
                staff, NOW, Map.of("orderId", orderId.toString())))).isEmpty();
        assertThat(enricher.enrich(new AuditEntry(tenant, Actions.REFUND_COMPLETED, "Refund", UUID.randomUUID(),
                staff, NOW, Map.of()))).isEmpty();
        assertThat(enricher.enrich(new AuditEntry(tenant, Actions.REFUND_COMPLETED, "Refund", UUID.randomUUID(),
                staff, NOW, Map.of("orderId", "khong-phai-uuid")))).isEmpty();
        verify(em, never()).find(SalesOrderJpaEntity.class, orderId);
    }

    @Test
    void donKhongTonTaiThiKhongLamGiau() {
        when(em.find(SalesOrderJpaEntity.class, orderId)).thenReturn(null);
        assertThat(enricher.enrich(orderEntry(Actions.ORDER_PAID, staff, Map.of()))).isEmpty();
    }

    @Test
    void chiMotLanTimDonChoMotDongAudit() {
        order(customer, "ONLINE", "PICKUP", 1L);
        enricher.enrich(orderEntry(Actions.ORDER_PAID, staff, Map.of()));
        verify(em).find(SalesOrderJpaEntity.class, orderId);
    }

    @Test
    void khongRoRiPiiChiCoKhoaTrongDanhSachChoPhep() {
        SalesOrderJpaEntity e = mock(SalesOrderJpaEntity.class);
        when(e.getCustomerUserId()).thenReturn(customer);
        when(e.getOrderNo()).thenReturn("SO-0001");
        when(e.getChannel()).thenReturn("ONLINE");
        when(e.getFulfilment()).thenReturn("DELIVERY");
        when(e.getGrandTotal()).thenReturn(1L);
        when(em.find(SalesOrderJpaEntity.class, orderId)).thenReturn(e);
        for (String action : new String[] {Actions.ORDER_CREATED, Actions.ORDER_CANCELLED, Actions.ORDER_PAID}) {
            Map<String, Object> x = enricher.enrich(orderEntry(action, staff, Map.of()));
            assertThat(ALLOWED).containsAll(x.keySet());
            x.values().forEach(v -> assertThat(String.valueOf(v)).doesNotContain("@"));
        }
        // enricher không bao giờ gọi các getter cá nhân của thực thể
        verify(e, never()).getRecipientName();
        verify(e, never()).getRecipientPhone();
        verify(e, never()).getRecipientAddress();
        verify(e, never()).getWalkInName();
        verify(e, never()).getWalkInPhone();
    }
}
