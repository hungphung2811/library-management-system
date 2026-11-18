package lms.sales.ordering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import lms.sales.ordering.application.PaymentIntentResponse;
import lms.sales.ordering.application.PlacePosOrderCommand;
import lms.sales.ordering.application.PlacePosOrderUseCase;
import lms.sales.ordering.application.PosOrderResult;
import lms.sales.ordering.application.ProcessPaymentWebhookUseCase.Command;
import lms.sales.ordering.domain.OrderStatus;
import lms.sales.ordering.domain.PaymentIntentStatus;
import lms.sales.ordering.domain.PaymentMethod;
import lms.sales.ordering.domain.PaymentStatus;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.domain.MovementType;
import lms.sales.support.Harness;
import lms.sales.support.QrFixture;
import org.junit.jupiter.api.Test;

/** Use case thanh toán QR của POS (ADR-0020) trên bộ giả trong bộ nhớ. */
class QrPaymentUseCaseTest {

    private final Harness h = new Harness();
    private final QrFixture fx = new QrFixture(h);

    private PlacePosOrderCommand qrCmd(UUID cid, UUID title, int qty) {
        return new PlacePosOrderCommand(h.tenant, h.user,
                List.of(new PlacePosOrderCommand.Line(title, qty, null)), PaymentMethod.QR, null, "Nguyễn Văn An",
                "0912345678", null, cid);
    }

    private PosOrderResult place(UUID title, int qty) {
        return h.tx(() -> fx.placePos.createWithPayment(qrCmd(UUID.randomUUID(), title, qty)));
    }

    private static ErrorCode codeOf(Runnable r) {
        return assertThrows(SalesException.class, r::run).code();
    }

    private Command paid(PaymentIntentResponse i, String txn) {
        return new Command(i.reference(), i.amount(), txn, "PAID");
    }

    @Test
    void taoDonQrGiuTonChuaTruVaTraLenhThanhToan() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        PosOrderResult r = place(a, 2);

        assertEquals(OrderStatus.AWAITING_PAYMENT, r.order().status());
        assertEquals(PaymentStatus.UNPAID, r.order().paymentStatus());
        assertEquals(PaymentMethod.QR, r.order().paymentMethod());
        assertEquals(200_000, r.order().grandTotal());
        PaymentIntentResponse i = r.paymentIntent();
        assertNotNull(i);
        assertEquals(PaymentIntentStatus.PENDING, i.status());
        assertEquals(200_000, i.amount());
        assertEquals("SIMULATED", i.provider());
        assertTrue(i.reference().matches("LMS[A-Z2-9]{10}"), i.reference());
        assertTrue(i.qrPayload().contains(i.reference()));
        assertTrue(i.qrPayload().contains("5406200000"), "số tiền trong QR");
        assertEquals(h.clock.instant().plusSeconds(600), i.expiresAt(), "mặc định 10 phút");
        // Hàng được giữ như đơn online: onHand còn nguyên, reserved tăng
        assertEquals(10, h.item(a).onHand());
        assertEquals(2, h.item(a).reserved());
        assertTrue(h.ledger().stream().anyMatch(m -> m.type() == MovementType.RESERVE));
        assertTrue(h.audits().stream().anyMatch(e -> e.action().equals(SalesAuditPort.Actions.ORDER_CREATED)));
    }

    private PlacePosOrderCommand cmd(PaymentMethod m, UUID title, int qty) {
        return new PlacePosOrderCommand(h.tenant, h.user, List.of(new PlacePosOrderCommand.Line(title, qty, null)), m,
                null, null, null, null, UUID.randomUUID());
    }

    @Test
    void donTienMatCungHaiBuocLenhManualKhongCoQrGiuTon() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        PosOrderResult r = h.tx(() -> fx.placePos.createWithPayment(cmd(PaymentMethod.CASH, a, 1)));
        assertEquals(OrderStatus.AWAITING_PAYMENT, r.order().status());
        assertEquals(PaymentStatus.UNPAID, r.order().paymentStatus());
        assertEquals("MANUAL", r.paymentIntent().provider());
        assertNull(r.paymentIntent().qrPayload());
        assertTrue(r.paymentIntent().reference().startsWith("LMS"));
        assertEquals(10, h.item(a).onHand());
        assertEquals(1, h.item(a).reserved());
        assertNull(h.orderRepo.findById(h.tenant, r.order().id()).orElseThrow().paidAt());
    }

    @Test
    void xacNhanTienMatXuatKhoGhiBanGhiThuVaTienThoi() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        PosOrderResult r = h.tx(() -> fx.placePos.createWithPayment(cmd(PaymentMethod.CASH, a, 2)));
        UUID cashier = UUID.randomUUID();

        PaymentIntentResponse c = h.tx(() -> fx.intentUseCases.confirm(h.tenant, cashier, r.paymentIntent().id(),
                new lms.sales.ordering.application.PaymentIntentUseCases.ConfirmCommand(500_000L, null)));

        assertEquals(PaymentIntentStatus.PAID, c.status());
        assertEquals(500_000L, c.tenderedAmount());
        assertEquals(300_000L, c.changeDue());
        var order = h.orderRepo.findById(h.tenant, r.order().id()).orElseThrow();
        assertEquals(OrderStatus.COMPLETED, order.status());
        assertEquals(PaymentMethod.CASH, order.payments().get(0).method());
        assertEquals(r.paymentIntent().reference(), order.payments().get(0).reference());
        assertEquals(cashier, order.payments().get(0).recordedBy());
        assertEquals(8, h.item(a).onHand());
        assertEquals(0, h.item(a).reserved());
        // xác nhận lại: 200 nguyên trạng, không thu hai lần
        var again = h.tx(() -> fx.intentUseCases.confirm(h.tenant, cashier, r.paymentIntent().id(), new lms.sales.ordering.application.PaymentIntentUseCases.ConfirmCommand(1L, null)));
        assertEquals(PaymentIntentStatus.PAID, again.status());
        assertEquals(1, h.orderRepo.findById(h.tenant, r.order().id()).orElseThrow().payments().size());
        assertEquals(8, h.item(a).onHand());
    }

    @Test
    void xacNhanTheCanMaGiaoDichVaLuuLamReference() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        PosOrderResult r = h.tx(() -> fx.placePos.createWithPayment(cmd(PaymentMethod.CARD, a, 1)));
        var noRef = new lms.sales.ordering.application.PaymentIntentUseCases.ConfirmCommand(null, null);
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> h.tx(() ->
                fx.intentUseCases.confirm(h.tenant, h.user, r.paymentIntent().id(), noRef))));
        assertEquals(1, h.item(a).reserved(), "lỗi nhập liệu không đổi gì");
        var c = h.tx(() -> fx.intentUseCases.confirm(h.tenant, h.user, r.paymentIntent().id(),
                new lms.sales.ordering.application.PaymentIntentUseCases.ConfirmCommand(null, "POS-778899")));
        assertEquals("POS-778899", c.transactionRef());
        assertNull(c.tenderedAmount());
        var order = h.orderRepo.findById(h.tenant, r.order().id()).orElseThrow();
        assertEquals(PaymentMethod.CARD, order.payments().get(0).method());
        assertEquals("POS-778899", order.payments().get(0).reference());
    }

    @Test
    void xacNhanLenhHetHanHoacDaHuyBi409VaLenhQrKhongXacNhanTay() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        PosOrderResult expiring = h.tx(() -> fx.placePos.createWithPayment(cmd(PaymentMethod.CASH, a, 1)));
        PosOrderResult cancelled = h.tx(() -> fx.placePos.createWithPayment(cmd(PaymentMethod.CASH, a, 1)));
        PosOrderResult qr = place(a, 1);
        var none = new lms.sales.ordering.application.PaymentIntentUseCases.ConfirmCommand(null, null);
        h.tx(() -> fx.intentUseCases.cancel(h.tenant, h.user, cancelled.paymentIntent().id()));
        assertEquals(ErrorCode.PAYMENT_INTENT_STATE_CONFLICT, codeOf(() -> h.tx(() ->
                fx.intentUseCases.confirm(h.tenant, h.user, cancelled.paymentIntent().id(), none))));
        assertEquals(ErrorCode.PAYMENT_NOT_MANUAL, codeOf(() -> h.tx(() ->
                fx.intentUseCases.confirm(h.tenant, h.user, qr.paymentIntent().id(), none))));
        h.clock.advanceSeconds(601);
        assertEquals(ErrorCode.PAYMENT_INTENT_STATE_CONFLICT, codeOf(() -> h.tx(() ->
                fx.intentUseCases.confirm(h.tenant, h.user, expiring.paymentIntent().id(), none))));
        // job dọn xử lý cả lệnh MANUAL: nhả tồn (lệnh đã hủy thì không còn PENDING)
        assertEquals(2, fx.expire.execute(h.tenant));
        assertEquals(0, h.item(a).reserved());
    }

    @Test
    void webhookKhongXacNhanDuocLenhManual() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        PosOrderResult r = h.tx(() -> fx.placePos.createWithPayment(cmd(PaymentMethod.CASH, a, 1)));
        assertEquals(ErrorCode.PAYMENT_NOT_SIMULATED, codeOf(() -> h.tx(() -> fx.webhook.execute(h.tenant,
                new Command(r.paymentIntent().reference(), r.paymentIntent().amount(), "T", "PAID")))));
    }

    @Test
    void donTongBangKhongThuNgayKhongCoLenh() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        var cmd = new PlacePosOrderCommand(h.tenant, h.user, List.of(new PlacePosOrderCommand.Line(a, 1, null)),
                PaymentMethod.CASH, null, null, null,
                new lms.sales.ordering.domain.Discount(lms.sales.ordering.domain.Discount.Type.PERCENT, 10_000L),
                UUID.randomUUID());
        PosOrderResult r = h.tx(() -> fx.placePos.createWithDiscountAndPayment(cmd));
        assertEquals(OrderStatus.COMPLETED, r.order().status());
        assertNull(r.paymentIntent());
        assertEquals(9, h.item(a).onHand());
    }

    @Test
    void hetHangKhiTaoDonQrRollbackKhongCoDonVaLenh() {
        UUID a = h.book("Sách A", 100_000, null, 1);
        assertEquals(ErrorCode.INSUFFICIENT_STOCK, codeOf(() -> place(a, 2)));
        assertEquals(0, h.orderCount());
        assertEquals(0, h.item(a).reserved());
    }

    @Test
    void guiLaiCungClientRequestIdTraLaiCungDonVaLenh() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        UUID cid = UUID.randomUUID();
        PosOrderResult first = h.tx(() -> fx.placePos.createWithPayment(qrCmd(cid, a, 1)));
        PosOrderResult again = h.tx(() -> fx.placePos.createWithPayment(qrCmd(cid, a, 1)));
        assertEquals(first.order().id(), again.order().id());
        assertEquals(first.paymentIntent().id(), again.paymentIntent().id());
        assertEquals(1, fx.intents.size());
        assertEquals(1, h.item(a).reserved(), "không giữ hàng hai lần");
    }

    @Test
    void qrKhongBatThiBiTuChoi400() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        var plain = new PlacePosOrderUseCase(h.orderRepo, h.catalog, null, null);
        var ex = assertThrows(SalesException.class,
                () -> plain.createWithPayment(qrCmd(UUID.randomUUID(), a, 1)));
        assertEquals(ErrorCode.PAYMENT_NOT_ENABLED, ex.code());
    }

    @Test
    void donDoiHangVanHaiBuocVaPhieuTraPhaiTonTai() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        var cmd = new PlacePosOrderCommand(h.tenant, h.user, List.of(new PlacePosOrderCommand.Line(a, 1, null)),
                PaymentMethod.QR, null, null, null, null, UUID.randomUUID(), UUID.randomUUID());
        assertEquals(ErrorCode.RETURN_NOT_FOUND, codeOf(() -> h.tx(() -> fx.placePos.createWithPayment(cmd))));
    }

    @Test
    void webhookThanhCongDonPaidTruTonVaGhiBanGhiThuQr() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        PosOrderResult r = place(a, 2);
        h.clock.advanceSeconds(120);

        var res = h.tx(() -> fx.webhook.execute(h.tenant, paid(r.paymentIntent(), "TXN-1")));

        assertFalse(res.duplicate());
        assertEquals(PaymentIntentStatus.PAID, res.intent().status());
        var order = h.orderRepo.findById(h.tenant, r.order().id()).orElseThrow();
        assertEquals(OrderStatus.COMPLETED, order.status());
        assertEquals(PaymentStatus.PAID, order.paymentStatus());
        assertEquals(1, order.payments().size());
        assertEquals(PaymentMethod.QR, order.payments().get(0).method());
        assertEquals(r.paymentIntent().reference(), order.payments().get(0).reference());
        assertEquals(8, h.item(a).onHand());
        assertEquals(0, h.item(a).reserved());
        assertTrue(h.ledger().stream().anyMatch(m -> m.type() == MovementType.FULFIL));
        assertTrue(h.audits().stream().anyMatch(e -> e.action().equals(SalesAuditPort.Actions.ORDER_PAID)));
    }

    @Test
    void webhookTrungMaGiaoDichIdempotentKhongThuHaiLan() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        PosOrderResult r = place(a, 2);

        h.tx(() -> fx.webhook.execute(h.tenant, paid(r.paymentIntent(), "TXN-1")));
        long paidAudits = h.audits().stream().filter(e -> e.action().equals(SalesAuditPort.Actions.ORDER_PAID)).count();
        var again = h.tx(() -> fx.webhook.execute(h.tenant, paid(r.paymentIntent(), "TXN-1")));

        assertTrue(again.duplicate());
        assertEquals(PaymentIntentStatus.PAID, again.intent().status());
        assertEquals(8, h.item(a).onHand(), "không trừ tồn lần hai");
        assertEquals(paidAudits, h.audits().stream()
                .filter(e -> e.action().equals(SalesAuditPort.Actions.ORDER_PAID)).count());
        assertEquals(1, h.orderRepo.findById(h.tenant, r.order().id()).orElseThrow().payments().size());
    }

    @Test
    void webhookMaGiaoDichKhacChoLenhDaThanhToanBi409() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        PosOrderResult r = place(a, 1);
        h.tx(() -> fx.webhook.execute(h.tenant, paid(r.paymentIntent(), "TXN-1")));
        assertEquals(ErrorCode.PAYMENT_INTENT_STATE_CONFLICT,
                codeOf(() -> h.tx(() -> fx.webhook.execute(h.tenant, paid(r.paymentIntent(), "TXN-2")))));
    }

    @Test
    void webhookLechTienBi400VaDonVanChoThanhToan() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        PosOrderResult r = place(a, 1);
        var bad = new Command(r.paymentIntent().reference(), 99_999, "TXN-1", "PAID");
        assertEquals(ErrorCode.PAYMENT_AMOUNT_MISMATCH, codeOf(() -> h.tx(() -> fx.webhook.execute(h.tenant, bad))));
        assertEquals(PaymentIntentStatus.PENDING, fx.intentUseCases.get(h.tenant, r.paymentIntent().id()).status());
        assertEquals(OrderStatus.AWAITING_PAYMENT,
                h.orderRepo.findById(h.tenant, r.order().id()).orElseThrow().status());
        assertEquals(1, h.item(a).reserved());
    }

    @Test
    void webhookSauKhiHetHanBiTuChoiVaKhongThuTien() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        PosOrderResult r = place(a, 1);
        h.clock.advanceSeconds(601);
        assertEquals(ErrorCode.PAYMENT_INTENT_EXPIRED,
                codeOf(() -> h.tx(() -> fx.webhook.execute(h.tenant, paid(r.paymentIntent(), "TXN-1")))));
        assertEquals(PaymentStatus.UNPAID, h.orderRepo.findById(h.tenant, r.order().id()).orElseThrow()
                .paymentStatus());
    }

    @Test
    void webhookTrangThaiThatBaiChiGhiNhanKhongDoiGi() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        PosOrderResult r = place(a, 1);
        var res = h.tx(() -> fx.webhook.execute(h.tenant,
                new Command(r.paymentIntent().reference(), r.paymentIntent().amount(), "T", "FAILED")));
        assertTrue(res.ignored());
        assertEquals(PaymentIntentStatus.PENDING, res.intent().status());
    }

    @Test
    void webhookMaThamChieuLaKhongTimThay404() {
        assertEquals(ErrorCode.PAYMENT_INTENT_NOT_FOUND, codeOf(() -> h.tx(() ->
                fx.webhook.execute(h.tenant, new Command("LMSNOPE", 1, "T", "PAID")))));
    }

    @Test
    void khongThayLenhCuaTenantKhac() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        PosOrderResult r = place(a, 1);
        UUID other = UUID.randomUUID();
        assertEquals(ErrorCode.PAYMENT_INTENT_NOT_FOUND,
                codeOf(() -> fx.intentUseCases.get(other, r.paymentIntent().id())));
        assertEquals(ErrorCode.PAYMENT_INTENT_NOT_FOUND, codeOf(() -> h.tx(() ->
                fx.webhook.execute(other, paid(r.paymentIntent(), "T")))));
        assertEquals(h.tenant, fx.resolver.findTenantIdByReference(r.paymentIntent().reference()).orElseThrow());
    }

    @Test
    void huyLenhNhaTonDonCancelledVaHuyLaiIdempotent() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        PosOrderResult r = place(a, 3);

        PaymentIntentResponse c = h.tx(() -> fx.intentUseCases.cancel(h.tenant, h.user, r.paymentIntent().id()));

        assertEquals(PaymentIntentStatus.CANCELLED, c.status());
        var order = h.orderRepo.findById(h.tenant, r.order().id()).orElseThrow();
        assertEquals(OrderStatus.CANCELLED, order.status());
        assertEquals("Hủy thanh toán QR", order.cancelReason());
        assertEquals(10, h.item(a).onHand());
        assertEquals(0, h.item(a).reserved());
        assertTrue(h.ledger().stream().anyMatch(m -> m.type() == MovementType.RELEASE));
        // hủy lại: giữ nguyên, không nhả tồn lần hai
        var again = h.tx(() -> fx.intentUseCases.cancel(h.tenant, h.user, r.paymentIntent().id()));
        assertEquals(PaymentIntentStatus.CANCELLED, again.status());
        assertEquals(0, h.item(a).reserved());
        // webhook sau khi hủy bị từ chối bằng mã riêng
        assertEquals(ErrorCode.PAYMENT_INTENT_CANCELLED,
                codeOf(() -> h.tx(() -> fx.webhook.execute(h.tenant, paid(r.paymentIntent(), "T")))));
    }

    @Test
    void khongHuyDuocLenhDaThanhToan() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        PosOrderResult r = place(a, 1);
        h.tx(() -> fx.webhook.execute(h.tenant, paid(r.paymentIntent(), "TXN-1")));
        assertEquals(ErrorCode.PAYMENT_INTENT_STATE_CONFLICT,
                codeOf(() -> h.tx(() -> fx.intentUseCases.cancel(h.tenant, h.user, r.paymentIntent().id()))));
        assertEquals(OrderStatus.COMPLETED, h.orderRepo.findById(h.tenant, r.order().id()).orElseThrow().status());
    }

    @Test
    void jobDonChiHetHanLenhQuaHanVaTraTon() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        PosOrderResult old = place(a, 2);
        h.clock.advanceSeconds(300);
        PosOrderResult fresh = place(a, 1);
        h.clock.advanceSeconds(301); // old quá 600 giây, fresh mới 301 giây

        assertEquals(1, fx.expire.execute(h.tenant));

        assertEquals(PaymentIntentStatus.EXPIRED, fx.intentUseCases.get(h.tenant, old.paymentIntent().id()).status());
        var oldOrder = h.orderRepo.findById(h.tenant, old.order().id()).orElseThrow();
        assertEquals(OrderStatus.CANCELLED, oldOrder.status());
        assertEquals("Hết hạn thanh toán QR", oldOrder.cancelReason());
        assertEquals(PaymentIntentStatus.PENDING,
                fx.intentUseCases.get(h.tenant, fresh.paymentIntent().id()).status());
        assertEquals(1, h.item(a).reserved(), "chỉ còn hàng giữ của lệnh chưa hết hạn");
        assertEquals(0, fx.expire.execute(h.tenant), "chạy lại không làm gì thêm");
    }

    @Test
    void layLenhBaoExpiredNgayKhiQuaHanDuJobChuaChay() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        PosOrderResult r = place(a, 1);
        h.clock.advanceSeconds(601);
        assertEquals(PaymentIntentStatus.EXPIRED, fx.intentUseCases.get(h.tenant, r.paymentIntent().id()).status());
    }
}
