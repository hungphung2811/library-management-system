package lms.sales.ordering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import lms.sales.ordering.application.AntiAbuseService;
import lms.sales.ordering.application.ApplyGatewayPaymentUseCase;
import lms.sales.ordering.application.CommitOnlineOrderUseCase;
import lms.sales.ordering.application.ExpirePaymentIntentsUseCase;
import lms.sales.ordering.application.PaymentAnomalyLogPort;
import lms.sales.ordering.application.PaymentGatewayPort;
import lms.sales.ordering.application.PlaceOnlineOrderCommand;
import lms.sales.ordering.application.PlaceOnlineOrderUseCase;
import lms.sales.ordering.application.ProcessPaymentWebhookUseCase;
import lms.sales.ordering.application.RecordPaymentAnomalyUseCase;
import lms.sales.ordering.application.ShopOrderResponse;
import lms.sales.ordering.application.ShopOrderUseCases;
import lms.sales.ordering.application.ShopPaymentResponse;
import lms.sales.ordering.application.ShopPaymentUseCases;
import lms.sales.ordering.application.SyncGatewayPaymentUseCase;
import lms.sales.ordering.domain.Fulfilment;
import lms.sales.ordering.domain.OrderStatus;
import lms.sales.ordering.domain.PaymentIntent;
import lms.sales.ordering.domain.PaymentIntentStatus;
import lms.sales.ordering.domain.PaymentMethod;
import lms.sales.ordering.domain.PaymentStatus;
import lms.sales.ordering.domain.Recipient;
import lms.sales.ordering.domain.SalesOrder;
import lms.sales.settings.application.UpdateSalesSettingsCommand;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.support.Harness;
import org.junit.jupiter.api.Test;

/**
 * Thanh toán online qua SEPAY của đơn website (ADR-0029) trên bộ giả trong bộ nhớ: đặt đơn AWAITING_PAYMENT, thăm dò ghi
 * PAID (PENDING_CONFIRMATION, không xuất kho), hết hạn/hủy nhả tồn, kiểm cổng lần cuối, trần đơn chờ, phí ship chốt lúc đặt.
 */
class OnlinePaymentUseCaseTest {

    private final Harness h = new Harness();
    private final UUID customer = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    private final AtomicReference<PaymentGatewayPort.GatewayPayment> found = new AtomicReference<>();
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
    private final List<String> queries = new java.util.ArrayList<>();

    private final PaymentGatewayPort sepay = new PaymentGatewayPort() {
        @Override
        public String provider() {
            return PaymentIntent.PROVIDER_SEPAY;
        }

        @Override
        public QrCharge createQr(ChargeRequest request) {
            return new QrCharge("QR-" + request.reference());
        }

        @Override
        public Optional<GatewayPayment> queryPayment(String reference, long amount, Instant notBefore) {
            queries.add(reference);
            if (failure.get() != null) {
                throw failure.get();
            }
            return Optional.ofNullable(found.get());
        }
    };

    private CommitOnlineOrderUseCase commit(boolean enabled, List<PaymentGatewayPort> gateways) {
        return new CommitOnlineOrderUseCase(h.orderRepo, h.stockService, h.getSettings, h.numbers, h.auditPort,
                h.tenantDirectory, h.clock, h.abusePolicies, (t, c) -> { },
                new AntiAbuseService(new AntiAbuseService.Thresholds(5, 30, 50, 80)), h.intents, gateways, enabled,
                Duration.ofMinutes(15));
    }

    private PlaceOnlineOrderUseCase placeUseCase(CommitOnlineOrderUseCase commit) {
        return new PlaceOnlineOrderUseCase(h.orderRepo, h.catalog, commit, h.abusePolicies);
    }

    private final PlaceOnlineOrderUseCase placeOn = placeUseCase(commit(true, List.of(sepay)));
    private final ProcessPaymentWebhookUseCase webhook = new ProcessPaymentWebhookUseCase(h.intents, h.orderRepo,
            h.stockService, h.auditPort, h.clock);
    private final SyncGatewayPaymentUseCase sync = new SyncGatewayPaymentUseCase(List.of(sepay),
            new ApplyGatewayPaymentUseCase(webhook, h.intents, mock(RecordPaymentAnomalyUseCase.class),
                    mock(PaymentAnomalyLogPort.class)));
    private final ShopPaymentUseCases shopPay = new ShopPaymentUseCases(h.orderRepo, h.intents, sync, h.intentUseCases,
            h.getSettings, h.clock, true, 15);
    private final ShopOrderUseCases shopOrders = new ShopOrderUseCases(h.orderRepo, h.workflow, shopPay);
    private final ExpirePaymentIntentsUseCase expire = new ExpirePaymentIntentsUseCase(h.intents, h.intentUseCases,
            h.clock, 100, sync);

    private PlaceOnlineOrderCommand cmd(UUID who, UUID cid, Fulfilment f, PaymentMethod pm, UUID title, int qty) {
        return new PlaceOnlineOrderCommand(h.tenant, who, List.of(new PlaceOnlineOrderCommand.Line(title, qty)), f, pm,
                new Recipient("Nguyễn Văn An", "0912345678", "12 Lê Lợi, Quận 1, TP HCM"), cid);
    }

    private ShopOrderResponse place(PlaceOnlineOrderUseCase uc, UUID title, int qty, Fulfilment f,
                                    PaymentMethod pm) {
        return h.tx(() -> uc.place(cmd(customer, UUID.randomUUID(), f, pm, title, qty)));
    }

    private ShopOrderResponse placeSepay(UUID title, int qty, Fulfilment f) {
        return place(placeOn, title, qty, f, PaymentMethod.SEPAY);
    }

    private void setShippingFee(long fee) {
        h.updateSettings.execute(new UpdateSalesSettingsCommand(h.tenant, h.user, 1000, true, 7, fee, 5, 12, 0));
    }

    private SalesOrder order(UUID id) {
        return h.orderRepo.findById(h.tenant, id).orElseThrow();
    }

    private PaymentIntent intentOf(UUID orderId) {
        return h.intents.findByOrderId(h.tenant, orderId).orElseThrow();
    }

    private static ErrorCode codeOf(Runnable r) {
        return assertThrows(SalesException.class, r::run).code();
    }

    private void bankReports(PaymentIntent i, Instant at) {
        found.set(new PaymentGatewayPort.GatewayPayment(true, "SEPAY-" + i.reference(), i.amount().amount(), at));
    }

    private ShopPaymentResponse poll(UUID orderId) {
        return shopPay.myOrderPayment(h.tenant, customer, orderId);
    }

    @Test
    void datSepayGiaoHangTaoDonChoThanhToanGiuTonVaChotPhiShipVaoTongTien() {
        setShippingFee(30_000);
        UUID a = h.book("Sách A", 100_000, null, 5);

        ShopOrderResponse o = placeSepay(a, 1, Fulfilment.DELIVERY);

        assertEquals(OrderStatus.AWAITING_PAYMENT, o.order().status());
        assertEquals(PaymentStatus.UNPAID, o.order().paymentStatus());
        assertEquals(PaymentMethod.SEPAY, o.order().paymentMethod());
        assertEquals(30_000, o.order().shippingFee());
        assertEquals(130_000, o.order().grandTotal());
        assertEquals(5, h.item(a).onHand());
        assertEquals(1, h.item(a).reserved());

        PaymentIntent i = intentOf(o.order().id());
        assertEquals(PaymentIntentStatus.PENDING, i.status());
        assertEquals("SEPAY", i.provider());
        assertEquals(130_000, i.amount().amount());
        assertEquals("QR-" + i.reference(), i.qrPayload());
        assertEquals(h.clock.instant().plus(Duration.ofMinutes(15)), i.expiresAt());
        assertNull(order(o.order().id()).reservationExpiresAt());

        var created = h.audits().stream().filter(x -> x.action().equals(SalesAuditPort.Actions.ORDER_CREATED))
                .findFirst().orElseThrow();
        assertEquals("ONLINE", created.data().get("channel"));
        assertEquals(i.id().toString(), created.data().get("paymentIntentId"));
    }

    @Test
    void datSepayNhanTaiCuaHangKhongThuPhiShip() {
        setShippingFee(30_000);
        UUID a = h.book("Sách A", 100_000, null, 5);
        ShopOrderResponse o = placeSepay(a, 1, Fulfilment.PICKUP);
        assertEquals(0, o.order().shippingFee());
        assertEquals(100_000, o.order().grandTotal());
    }

    @Test
    void sepayTatHoacChuaCoCongThi400PaymentNotEnabledKhongTaoDonKhongGiuTon() {
        UUID a = h.book("Sách A", 100_000, null, 5);
        PlaceOnlineOrderUseCase off = placeUseCase(commit(false, List.of(sepay)));
        PlaceOnlineOrderUseCase noGateway = placeUseCase(commit(true, List.of()));

        assertEquals(ErrorCode.PAYMENT_NOT_ENABLED,
                codeOf(() -> place(off, a, 1, Fulfilment.PICKUP, PaymentMethod.SEPAY)));
        assertEquals(ErrorCode.PAYMENT_NOT_ENABLED,
                codeOf(() -> place(noGateway, a, 1, Fulfilment.PICKUP, PaymentMethod.SEPAY)));
        assertEquals(0, h.orderCount());
        assertEquals(0, h.item(a).reserved());
    }

    @Test
    void qrMomoCardTrenDonOnlineBiTuChoi400InvalidRequestDuCoSepay() {
        UUID a = h.book("Sách A", 100_000, null, 5);
        for (PaymentMethod m : new PaymentMethod[] {PaymentMethod.QR, PaymentMethod.MOMO, PaymentMethod.CARD}) {
            assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> place(placeOn, a, 1, Fulfilment.PICKUP, m)),
                    m.name());
        }
        assertEquals(0, h.orderCount());
        assertEquals(0, h.item(a).reserved());
    }

    @Test
    void cacPhuongThucCuVanDiLuongCuPendingConfirmationUnpaid() {
        UUID a = h.book("Sách A", 100_000, null, 5);
        ShopOrderResponse o = place(placeOn, a, 1, Fulfilment.DELIVERY, PaymentMethod.COD);
        assertEquals(OrderStatus.PENDING_CONFIRMATION, o.order().status());
        assertEquals(PaymentStatus.UNPAID, o.order().paymentStatus());
        assertTrue(h.intents.findByOrderId(h.tenant, o.order().id()).isEmpty());
    }

    @Test
    void thamDoThayTienVePaidChoXacNhanKhongXuatKhoRoiGiaoNhuDonThuong() {
        setShippingFee(30_000);
        UUID a = h.book("Sách A", 100_000, null, 5);
        UUID id = placeSepay(a, 1, Fulfilment.DELIVERY).order().id();
        bankReports(intentOf(id), null);

        ShopPaymentResponse r = poll(id);

        assertEquals(PaymentIntentStatus.PAID, r.intent().status());
        assertEquals(OrderStatus.PENDING_CONFIRMATION, r.orderStatus());
        assertEquals(PaymentStatus.PAID, r.paymentStatus());
        assertEquals(5, h.item(a).onHand(), "không xuất kho khi mới trả tiền");
        assertEquals(1, h.item(a).reserved(), "tồn vẫn giữ");
        SalesOrder o = order(id);
        assertEquals(1, o.payments().size());
        assertEquals(PaymentMethod.SEPAY, o.payments().get(0).method());
        var paidAudit = h.audits().stream().filter(x -> x.action().equals(SalesAuditPort.Actions.ORDER_PAID))
                .findFirst().orElseThrow();
        assertEquals("ONLINE", paidAudit.data().get("channel"));

        // nhân viên: không đổi được phí ship của đơn đã trả
        assertEquals(ErrorCode.ORDER_STATE_CONFLICT,
                codeOf(() -> h.workflow.confirm(h.tenant, h.user, id, 50_000L, null)));
        var confirmed = h.workflow.confirm(h.tenant, h.user, id, null, null);
        assertEquals(OrderStatus.CONFIRMED, confirmed.status());
        assertEquals(30_000, confirmed.shippingFee());
        assertEquals(130_000, confirmed.grandTotal(), "không tính lại tổng tiền đơn đã trả");

        h.workflow.markShipping(h.tenant, h.user, id, null);
        assertEquals(4, h.item(a).onHand(), "xuất kho khi hàng rời cửa hàng");
        assertEquals(0, h.item(a).reserved());
        assertEquals(OrderStatus.COMPLETED, h.workflow.complete(h.tenant, h.user, id, null).status());
    }

    @Test
    void thamDoChuaCoTienHoacCongLoiGiuPendingVaKhongLamHongPhanHoi() {
        UUID a = h.book("Sách A", 100_000, null, 5);
        UUID id = placeSepay(a, 1, Fulfilment.PICKUP).order().id();

        ShopPaymentResponse r = poll(id);
        assertEquals(PaymentIntentStatus.PENDING, r.intent().status());
        assertNotNull(r.intent().qrPayload());
        assertEquals(OrderStatus.AWAITING_PAYMENT, r.orderStatus());

        failure.set(new SalesException(ErrorCode.PAYMENT_GATEWAY_ERROR, "429"));
        assertEquals(PaymentIntentStatus.PENDING, poll(id).intent().status());
        assertEquals(2, queries.size());
    }

    @Test
    void tienLechSoTienKhongDuocGhiNhanLenhGiuPending() {
        UUID a = h.book("Sách A", 100_000, null, 5);
        UUID id = placeSepay(a, 1, Fulfilment.PICKUP).order().id();
        PaymentIntent i = intentOf(id);
        found.set(new PaymentGatewayPort.GatewayPayment(true, "SEPAY-x", i.amount().amount() - 1));

        assertEquals(PaymentIntentStatus.PENDING, poll(id).intent().status());
        assertEquals(PaymentStatus.UNPAID, order(id).paymentStatus());
    }

    @Test
    void donCuaNguoiKhacHoacKhongTonTaiCungTra404VaKhachPhaiDangNhap() {
        UUID a = h.book("Sách A", 100_000, null, 5);
        UUID id = placeSepay(a, 1, Fulfilment.PICKUP).order().id();

        assertEquals(ErrorCode.ORDER_NOT_FOUND, codeOf(() -> shopPay.myOrderPayment(h.tenant, other, id)));
        assertEquals(ErrorCode.ORDER_NOT_FOUND,
                codeOf(() -> shopPay.myOrderPayment(h.tenant, customer, UUID.randomUUID())));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> shopPay.myOrderPayment(h.tenant, null, id)));
        assertEquals(ErrorCode.ORDER_NOT_FOUND, codeOf(() -> shopOrders.cancelMyOrder(h.tenant, other, id, null)));
    }

    @Test
    void hetHanNhaTonVaHuyDonSauKhiHoiCongLanCuoiKhongThayTien() {
        UUID a = h.book("Sách A", 100_000, null, 5);
        UUID id = placeSepay(a, 2, Fulfilment.PICKUP).order().id();
        PaymentIntent before = intentOf(id);
        h.clock.advance(Duration.ofMinutes(16));

        assertEquals(1, expire.execute(h.tenant));

        assertEquals(OrderStatus.CANCELLED, order(id).status());
        assertEquals(PaymentIntentStatus.EXPIRED, intentOf(id).status());
        assertEquals(0, h.item(a).reserved());
        assertEquals(5, h.item(a).onHand());
        assertEquals(List.of(before.reference()), queries, "hỏi cổng đúng một lần trước khi hết hạn");
    }

    @Test
    void hetHanNhungCongBaoTienVeTruocHanThiGhiPaidThayVeHuy() {
        UUID a = h.book("Sách A", 100_000, null, 5);
        UUID id = placeSepay(a, 1, Fulfilment.PICKUP).order().id();
        PaymentIntent i = intentOf(id);
        bankReports(i, i.createdAt().plusSeconds(600)); // trước hạn 15 phút
        h.clock.advance(Duration.ofMinutes(16));

        assertEquals(0, expire.execute(h.tenant), "không hết hạn vì đã nhận tiền");

        assertEquals(PaymentIntentStatus.PAID, intentOf(id).status());
        assertEquals(OrderStatus.PENDING_CONFIRMATION, order(id).status());
        assertEquals(PaymentStatus.PAID, order(id).paymentStatus());
        assertEquals(1, h.item(a).reserved());
    }

    @Test
    void hetHanVaGiaoDichSauHanThiVanHetHanNhaTon() {
        UUID a = h.book("Sách A", 100_000, null, 5);
        UUID id = placeSepay(a, 1, Fulfilment.PICKUP).order().id();
        PaymentIntent i = intentOf(id);
        bankReports(i, i.expiresAt().plusSeconds(5));
        h.clock.advance(Duration.ofMinutes(16));

        assertEquals(1, expire.execute(h.tenant));

        assertEquals(OrderStatus.CANCELLED, order(id).status());
        assertEquals(0, h.item(a).reserved());
    }

    @Test
    void hetHanCongLoiThiVanHetHan() {
        UUID a = h.book("Sách A", 100_000, null, 5);
        UUID id = placeSepay(a, 1, Fulfilment.PICKUP).order().id();
        failure.set(new SalesException(ErrorCode.PAYMENT_GATEWAY_ERROR, "down"));
        h.clock.advance(Duration.ofMinutes(16));

        assertEquals(1, expire.execute(h.tenant));
        assertEquals(OrderStatus.CANCELLED, order(id).status());
    }

    @Test
    void khachHuyKhiChoThanhToanHuyLenhVaDonVaNhaTon() {
        UUID a = h.book("Sách A", 100_000, null, 5);
        UUID id = placeSepay(a, 2, Fulfilment.PICKUP).order().id();

        ShopOrderResponse r = shopOrders.cancelMyOrder(h.tenant, customer, id, "Đổi ý");

        assertEquals(OrderStatus.CANCELLED, r.order().status());
        assertEquals("Nguyễn Văn An", r.recipient().name());
        assertEquals(PaymentIntentStatus.CANCELLED, intentOf(id).status());
        assertEquals("Đổi ý", order(id).cancelReason());
        assertEquals(0, h.item(a).reserved());
        assertEquals(1, queries.size(), "hỏi cổng lần cuối trước khi hủy");
    }

    @Test
    void khachHuyNhungCongBaoVuaNhanTienThi409VaDonThanhPaid() {
        UUID a = h.book("Sách A", 100_000, null, 5);
        UUID id = placeSepay(a, 1, Fulfilment.PICKUP).order().id();
        bankReports(intentOf(id), null);

        assertEquals(ErrorCode.ORDER_STATE_CONFLICT, codeOf(() -> shopOrders.cancelMyOrder(h.tenant, customer, id,
                null)));

        assertEquals(OrderStatus.PENDING_CONFIRMATION, order(id).status());
        assertEquals(PaymentStatus.PAID, order(id).paymentStatus());
        assertEquals(PaymentIntentStatus.PAID, intentOf(id).status());
        assertEquals(1, h.item(a).reserved());
    }

    @Test
    void khachKhongTuHuyDonDaThanhToanNhungNhanVienHuyThiNhaTonVaCoKhoanHoan() {
        UUID a = h.book("Sách A", 100_000, null, 5);
        UUID id = placeSepay(a, 1, Fulfilment.PICKUP).order().id();
        bankReports(intentOf(id), null);
        poll(id);

        assertEquals(ErrorCode.ORDER_STATE_CONFLICT, codeOf(() -> shopOrders.cancelMyOrder(h.tenant, customer, id,
                null)));
        assertEquals(OrderStatus.PENDING_CONFIRMATION, order(id).status());

        var cancelled = h.workflow.cancel(h.tenant, h.user, id, "Hết hàng", null);
        assertEquals(OrderStatus.CANCELLED, cancelled.status());
        assertEquals(0, h.item(a).reserved());
        assertTrue(h.audits().stream().anyMatch(x -> x.action().equals(SalesAuditPort.Actions.REFUND_CREATED)));
    }

    @Test
    void tranDonChoTinhCaDonChoThanhToanVaHuyHieuDemCung() {
        h.setAbusePolicy(15, 1, 20, 50, 100);
        UUID a = h.book("Sách A", 100_000, null, 10);
        placeSepay(a, 1, Fulfilment.PICKUP);

        assertEquals(ErrorCode.ORDER_LIMIT_EXCEEDED, codeOf(() -> placeSepay(a, 1, Fulfilment.PICKUP)));
        assertEquals(1, h.item(a).reserved());
        assertEquals(1, shopOrders.myOrderCounts(h.tenant, customer).pending());
    }

    @Test
    void guiLaiCungClientRequestIdTraDonCuKhongTaoThemLenhHayGiuTon() {
        UUID a = h.book("Sách A", 100_000, null, 5);
        UUID cid = UUID.randomUUID();
        ShopOrderResponse first = h.tx(() -> placeOn.place(cmd(customer, cid, Fulfilment.PICKUP,
                PaymentMethod.SEPAY, a, 1)));
        ShopOrderResponse again = h.tx(() -> placeOn.place(cmd(customer, cid, Fulfilment.PICKUP,
                PaymentMethod.SEPAY, a, 1)));

        assertEquals(first.order().id(), again.order().id());
        assertEquals(1, h.item(a).reserved());
        assertEquals(1, h.orderCount());
        // cùng khóa nhưng đổi hình thức thanh toán: 422
        assertEquals(ErrorCode.IDEMPOTENCY_KEY_REUSED, codeOf(() -> placeOn.place(cmd(customer, cid,
                Fulfilment.PICKUP, PaymentMethod.CASH, a, 1))));
    }

    @Test
    void tuyChonThanhToanCoSepayChiKhiBatVaKemPhiGiaoHangCoDinh() {
        setShippingFee(30_000);
        ShopPaymentUseCases.PaymentOptions on = shopPay.paymentOptions(h.tenant);
        assertEquals(30_000, on.deliveryFee());
        assertEquals(List.of(PaymentMethod.COD, PaymentMethod.CASH, PaymentMethod.BANK_TRANSFER, PaymentMethod.SEPAY),
                on.methods().stream().map(ShopPaymentUseCases.PaymentOption::method).collect(Collectors.toList()));
        var sepayOption = on.methods().get(3);
        assertEquals(List.of(Fulfilment.PICKUP, Fulfilment.DELIVERY), sepayOption.fulfilments());
        assertEquals(15, sepayOption.intentTtlMinutes());
        assertNull(on.methods().get(0).intentTtlMinutes());
        assertEquals(List.of(Fulfilment.DELIVERY), on.methods().get(0).fulfilments());
        assertEquals(List.of(Fulfilment.PICKUP), on.methods().get(1).fulfilments());

        ShopPaymentUseCases disabled = new ShopPaymentUseCases(h.orderRepo, h.intents, sync, h.intentUseCases,
                h.getSettings, h.clock, false, 15);
        assertFalse(disabled.paymentOptions(h.tenant).methods().stream()
                .anyMatch(m -> m.method() == PaymentMethod.SEPAY));
    }

    @Test
    void donKhongQuaCongTraIntentNullCungKhongHoiCong() {
        UUID a = h.book("Sách A", 100_000, null, 5);
        UUID id = place(placeOn, a, 1, Fulfilment.PICKUP, PaymentMethod.CASH).order().id();

        ShopPaymentResponse r = poll(id);

        assertNull(r.intent());
        assertEquals(OrderStatus.PENDING_CONFIRMATION, r.orderStatus());
        assertEquals(0, queries.size());
    }
}
