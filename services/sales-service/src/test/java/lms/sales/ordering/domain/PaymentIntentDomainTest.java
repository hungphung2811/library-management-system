package lms.sales.ordering.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.Rate;
import lms.sales.shared.domain.SalesException;
import org.junit.jupiter.api.Test;

/** Máy trạng thái lệnh thanh toán QR và các chuyển trạng thái QR của đơn POS (ADR-0020). */
class PaymentIntentDomainTest {

    private static final Instant T0 = Instant.parse("2026-11-17T03:00:00Z");
    private static final Instant EXP = T0.plusSeconds(600);
    private final UUID tenant = UUID.randomUUID();

    private PaymentIntent intent(long amount) {
        return PaymentIntent.create(tenant, UUID.randomUUID(), Money.of(amount), "LMSABCDEFGHIJ", "SIMULATED", "qr",
                T0, EXP);
    }

    private static ErrorCode codeOf(Runnable r) {
        return assertThrows(SalesException.class, r::run).code();
    }

    @Test
    void lenhMoiOPendingChuaCoGiaoDich() {
        PaymentIntent i = intent(200_000);
        assertEquals(PaymentIntentStatus.PENDING, i.status());
        assertNull(i.providerTxnId());
        assertNull(i.paidAt());
        assertFalse(i.isExpiredAt(T0.plusSeconds(599)));
        assertTrue(i.isExpiredAt(EXP), "đúng mốc hạn thì đã hết hạn");
    }

    @Test
    void khongTaoLenhSoTienKhongAmHoacHanTrongQuaKhu() {
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> intent(0)));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> PaymentIntent.create(tenant, UUID.randomUUID(),
                Money.of(1000), "LMSX", "SIMULATED", "qr", T0, T0)));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> PaymentIntent.create(tenant, UUID.randomUUID(),
                Money.of(1000), " ", "SIMULATED", "qr", T0, EXP)));
    }

    @Test
    void markPaidDungTienThanhPaidGhiMaGiaoDich() {
        PaymentIntent i = intent(200_000);
        i.markPaid(" TXN-1 ", Money.of(200_000), T0.plusSeconds(60));
        assertEquals(PaymentIntentStatus.PAID, i.status());
        assertEquals("TXN-1", i.providerTxnId());
        assertEquals(T0.plusSeconds(60), i.paidAt());
    }

    @Test
    void markPaidLechTien400VaKhongDoiTrangThai() {
        PaymentIntent i = intent(200_000);
        assertEquals(ErrorCode.PAYMENT_AMOUNT_MISMATCH, codeOf(() -> i.markPaid("T", Money.of(199_999), T0)));
        assertEquals(ErrorCode.PAYMENT_AMOUNT_MISMATCH, codeOf(() -> i.markPaid("T", Money.of(200_001), T0)));
        assertEquals(PaymentIntentStatus.PENDING, i.status());
    }

    @Test
    void markPaidQuaHanBiTuChoiDuNhoHonHanLaDungMocHan() {
        PaymentIntent i = intent(1000);
        assertEquals(ErrorCode.PAYMENT_INTENT_EXPIRED, codeOf(() -> i.markPaid("T", Money.of(1000), EXP)));
        assertEquals(PaymentIntentStatus.PENDING, i.status(), "trạng thái đổi bởi job dọn, không bởi webhook");
    }

    @Test
    void markPaidThieuMaGiaoDichBiTuChoi() {
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> intent(1000).markPaid(" ", Money.of(1000), T0)));
    }

    @Test
    void expireChiKhiQuaHanVaLaTrangThaiCuoi() {
        PaymentIntent i = intent(1000);
        assertEquals(ErrorCode.PAYMENT_INTENT_STATE_CONFLICT, codeOf(() -> i.expire(T0.plusSeconds(10))));
        i.expire(EXP);
        assertEquals(PaymentIntentStatus.EXPIRED, i.status());
        assertEquals(ErrorCode.PAYMENT_INTENT_EXPIRED, codeOf(() -> i.markPaid("T", Money.of(1000), T0)));
        assertEquals(ErrorCode.PAYMENT_INTENT_STATE_CONFLICT, codeOf(i::cancel));
        assertFalse(i.isExpiredAt(EXP.plusSeconds(1)), "đã EXPIRED thì không còn tính là PENDING quá hạn");
    }

    @Test
    void cancelChiTuPendingVaChanThanhToanSau() {
        PaymentIntent i = intent(1000);
        i.cancel();
        assertEquals(PaymentIntentStatus.CANCELLED, i.status());
        assertEquals(ErrorCode.PAYMENT_INTENT_CANCELLED, codeOf(() -> i.markPaid("T", Money.of(1000), T0)));
        assertEquals(ErrorCode.PAYMENT_INTENT_STATE_CONFLICT, codeOf(i::cancel));
    }

    @Test
    void daPaidThiKhongHuyKhongHetHan() {
        PaymentIntent i = intent(1000);
        i.markPaid("T", Money.of(1000), T0);
        assertEquals(ErrorCode.PAYMENT_INTENT_STATE_CONFLICT, codeOf(i::cancel));
        assertEquals(ErrorCode.PAYMENT_INTENT_STATE_CONFLICT, codeOf(() -> i.expire(EXP.plusSeconds(1))));
        assertEquals(ErrorCode.PAYMENT_INTENT_STATE_CONFLICT, codeOf(() -> i.markPaid("T2", Money.of(1000), T0)));
    }

    // ---- đơn POS QR ----

    private static final PriceCalculator.TaxPolicy TAX = new PriceCalculator.TaxPolicy(Rate.ofBps(0), true);

    private SalesOrder qrOrder() {
        var draft = new SalesOrder.LineDraft(UUID.randomUUID(), "Sách", "ISBN", Money.of(100_000), 2, null);
        return SalesOrder.createPosAwaitingPayment(tenant, "O-1", UUID.randomUUID(), T0, PaymentMethod.QR, null, "An",
                "0900000000", List.of(draft), null, TAX, UUID.randomUUID(), null, null);
    }

    @Test
    void donQrChoThanhToanChuaThuTien() {
        SalesOrder o = qrOrder();
        assertEquals(OrderStatus.AWAITING_PAYMENT, o.status());
        assertEquals(PaymentStatus.UNPAID, o.paymentStatus());
        assertEquals(PaymentMethod.QR, o.paymentMethod());
        assertEquals(OrderChannel.POS, o.channel());
        assertTrue(o.payments().isEmpty());
        assertNull(o.paidAt());
        assertEquals(200_000, o.grandTotal().amount());
    }

    @Test
    void settleQrThanhCompletedPaidKemBanGhiThuQr() {
        SalesOrder o = qrOrder();
        PaymentRecord p = o.settleQrPayment("LMSREF", T0.plusSeconds(30));
        assertEquals(OrderStatus.COMPLETED, o.status());
        assertEquals(PaymentStatus.PAID, o.paymentStatus());
        assertEquals(PaymentMethod.QR, p.method());
        assertEquals("LMSREF", p.reference());
        assertEquals(200_000, p.amount().amount());
        assertEquals(1, o.payments().size());
        assertEquals(T0.plusSeconds(30), o.paidAt());
        assertEquals(T0.plusSeconds(30), o.completedAt());
        // thu hai lần hoặc hủy sau khi thu đều bị chặn
        assertEquals(ErrorCode.ORDER_STATE_CONFLICT, codeOf(() -> o.settleQrPayment("X", T0)));
        assertEquals(ErrorCode.ORDER_STATE_CONFLICT, codeOf(() -> o.cancelAwaitingPayment("lý do", null, T0)));
    }

    @Test
    void huyDonChoThanhToanCanLyDoVaChiMotLan() {
        SalesOrder o = qrOrder();
        assertEquals(ErrorCode.REASON_REQUIRED, codeOf(() -> o.cancelAwaitingPayment(" ", null, T0)));
        o.cancelAwaitingPayment(" Hết hạn ", null, T0);
        assertEquals(OrderStatus.CANCELLED, o.status());
        assertEquals("Hết hạn", o.cancelReason());
        assertEquals(PaymentStatus.UNPAID, o.paymentStatus());
        assertNull(o.refundDueOnCancel(), "chưa thu thì không có khoản hoàn");
        assertEquals(ErrorCode.ORDER_STATE_CONFLICT, codeOf(() -> o.settleQrPayment("X", T0)));
    }

    @Test
    void createPosThuongKhongChoPhepQrVaOnlineKhongNhanQr() {
        var draft = new SalesOrder.LineDraft(UUID.randomUUID(), "Sách", "ISBN", Money.of(100_000), 1, null);
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> SalesOrder.createPos(tenant, "O-2", UUID.randomUUID(),
                T0, PaymentMethod.QR, null, null, null, List.of(draft), null, TAX, UUID.randomUUID())));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> SalesOrder.placeOnline(tenant, "O-3", UUID.randomUUID(),
                T0, PaymentMethod.QR, Fulfilment.PICKUP, new Recipient("An", "0900000000", null), List.of(draft),
                TAX, UUID.randomUUID())));
    }

    @Test
    void donHaiBuocChoMoiPhuongThucPosVaChoPhepTongBangKhong() {
        var draft = new SalesOrder.LineDraft(UUID.randomUUID(), "Sách", "ISBN", Money.of(100_000), 1, null);
        for (PaymentMethod m : List.of(PaymentMethod.CASH, PaymentMethod.CARD, PaymentMethod.BANK_TRANSFER)) {
            SalesOrder o = SalesOrder.createPosAwaitingPayment(tenant, "O-5", UUID.randomUUID(), T0, m, null, null,
                    null, List.of(draft), null, TAX, UUID.randomUUID(), null, null);
            assertEquals(OrderStatus.AWAITING_PAYMENT, o.status());
            assertEquals(m, o.paymentMethod());
            PaymentRecord p = o.settleAwaitingPayment("REF", UUID.randomUUID(), T0);
            assertEquals(m, p.method(), "bản ghi thu mang phương thức thật");
            assertEquals(ErrorCode.ORDER_STATE_CONFLICT, codeOf(() -> o.settleQrPayment("X", T0)));
        }
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> SalesOrder.createPosAwaitingPayment(tenant, "O-6",
                UUID.randomUUID(), T0, PaymentMethod.COD, null, null, null, List.of(draft), null, TAX,
                UUID.randomUUID(), null, null)));
        var free = new SalesOrder.LineDraft(UUID.randomUUID(), "Sách", "ISBN", Money.ZERO, 1, null);
        assertTrue(SalesOrder.createPosAwaitingPayment(tenant, "O-4", UUID.randomUUID(), T0, PaymentMethod.CASH,
                null, null, null, List.of(free), null, TAX, UUID.randomUUID(), null, null).grandTotal().isZero());
    }

    // ---- xác nhận thu tay (ADR-0021) ----

    private PaymentIntent manual(long amount) {
        return PaymentIntent.create(tenant, UUID.randomUUID(), Money.of(amount), "LMSMANUAL001",
                PaymentIntent.PROVIDER_MANUAL, null, T0, EXP);
    }

    @Test
    void thuTienMatTinhTienThoiVaMacDinhBangDungSoTien() {
        PaymentIntent a = manual(215_000);
        a.confirmManual(PaymentMethod.CASH, null, 300_000L, T0.plusSeconds(5));
        assertEquals(PaymentIntentStatus.PAID, a.status());
        assertEquals(300_000L, a.tenderedAmount());
        assertEquals(85_000L, a.changeDue());
        assertNull(a.transactionRef());
        assertTrue(a.providerTxnId().startsWith("MANUAL-"));

        PaymentIntent b = manual(1000);
        b.confirmManual(PaymentMethod.CASH, "  ", null, T0);
        assertEquals(1000L, b.tenderedAmount());
        assertEquals(0L, b.changeDue());
    }

    @Test
    void thuTienMatThieuTienBi400() {
        PaymentIntent a = manual(1000);
        assertEquals(ErrorCode.PAYMENT_AMOUNT_MISMATCH,
                codeOf(() -> a.confirmManual(PaymentMethod.CASH, null, 999L, T0)));
        assertEquals(PaymentIntentStatus.PENDING, a.status());
    }

    @Test
    void theVaChuyenKhoanCanMaGiaoDichVaKhongCoTienKhachDua() {
        PaymentIntent a = manual(1000);
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> a.confirmManual(PaymentMethod.CARD, null, null, T0)));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> a.confirmManual(PaymentMethod.CARD, "R", 1000L, T0)));
        assertEquals(ErrorCode.INVALID_REQUEST,
                codeOf(() -> a.confirmManual(PaymentMethod.BANK_TRANSFER, "x".repeat(65), null, T0)));
        a.confirmManual(PaymentMethod.BANK_TRANSFER, " FT123 ", null, T0);
        assertEquals("FT123", a.transactionRef());
        assertNull(a.tenderedAmount());
        assertNull(a.changeDue());
    }

    @Test
    void khongXacNhanTayLenhQrHetHanHoacDaHuy() {
        assertEquals(ErrorCode.PAYMENT_NOT_MANUAL,
                codeOf(() -> intent(1000).confirmManual(PaymentMethod.CASH, null, null, T0)));
        PaymentIntent expired = manual(1000);
        assertEquals(ErrorCode.PAYMENT_INTENT_STATE_CONFLICT,
                codeOf(() -> expired.confirmManual(PaymentMethod.CASH, null, null, EXP)));
        PaymentIntent cancelled = manual(1000);
        cancelled.cancel();
        assertEquals(ErrorCode.PAYMENT_INTENT_STATE_CONFLICT,
                codeOf(() -> cancelled.confirmManual(PaymentMethod.CASH, null, null, T0)));
    }
}
