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

/**
 * Đơn ONLINE trả trước qua SEPAY (ADR-0029): AWAITING_PAYMENT, thanh toán xong sang PENDING_CONFIRMATION + PAID mà không
 * xuất kho, phí ship chốt lúc đặt, khách không tự hủy đơn đã trả; cùng với P1 (giao dịch trước hạn vẫn được nhận).
 */
class OnlinePaymentDomainTest {

    private static final Instant T0 = Instant.parse("2026-11-19T03:00:00Z");
    private static final PriceCalculator.TaxPolicy TAX = new PriceCalculator.TaxPolicy(Rate.ofBps(500), true);
    private static final Recipient RECIPIENT = new Recipient("Nguyễn Văn A", "0900000000", "1 Lê Lợi");
    private final UUID tenant = UUID.randomUUID();
    private final UUID customer = UUID.randomUUID();
    private final UUID staff = UUID.randomUUID();

    private static List<SalesOrder.LineDraft> drafts() {
        return List.of(new SalesOrder.LineDraft(UUID.randomUUID(), "Sách", "ISBN", Money.of(100_000), 2, null));
    }

    private SalesOrder awaiting(PaymentMethod method, Fulfilment fulfilment, long fee) {
        return SalesOrder.placeOnlineAwaitingPayment(tenant, "O-1", customer, T0, method, fulfilment, RECIPIENT,
                drafts(), TAX, UUID.randomUUID(), null, Money.of(fee), "hash");
    }

    private static ErrorCode codeOf(Runnable r) {
        return assertThrows(SalesException.class, r::run).code();
    }

    @Test
    void donGiaoHangChoThanhToanCoPhiShipTrongTongTienVaChuaCoHanGiuHang() {
        SalesOrder o = awaiting(PaymentMethod.SEPAY, Fulfilment.DELIVERY, 30_000);
        assertEquals(OrderChannel.ONLINE, o.channel());
        assertEquals(OrderStatus.AWAITING_PAYMENT, o.status());
        assertEquals(PaymentStatus.UNPAID, o.paymentStatus());
        assertEquals(30_000, o.shippingFee().amount());
        assertEquals(230_000, o.grandTotal().amount());
        assertNull(o.reservationExpiresAt(), "hạn giữ hàng do lệnh thanh toán quyết định");
        assertEquals("hash", o.requestHash());
        assertTrue(o.payments().isEmpty());
    }

    @Test
    void donNhanTaiCuaHangKhongThuPhiShipDuTruyenPhi() {
        SalesOrder o = awaiting(PaymentMethod.SEPAY, Fulfilment.PICKUP, 30_000);
        assertEquals(0, o.shippingFee().amount());
        assertEquals(200_000, o.grandTotal().amount());
    }

    @Test
    void chiSepayDuocLamCongOnlineQrMomoCardVaCacPhuongThucKhacBiTuChoi() {
        for (PaymentMethod m : new PaymentMethod[] {PaymentMethod.QR, PaymentMethod.MOMO, PaymentMethod.CARD,
                PaymentMethod.COD, PaymentMethod.CASH, PaymentMethod.BANK_TRANSFER}) {
            assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> awaiting(m, Fulfilment.PICKUP, 0)), m.name());
        }
        assertTrue(PaymentMethod.SEPAY.allowedOnlineGateway());
        assertFalse(PaymentMethod.QR.allowedOnlineGateway());
        assertFalse(PaymentMethod.MOMO.allowedOnlineGateway());
    }

    @Test
    void donOnlineQuaCongKhongNhanhinhThucQuayVaThieuNguoiNhan() {
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> awaiting(PaymentMethod.SEPAY, Fulfilment.COUNTER, 0)));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> SalesOrder.placeOnlineAwaitingPayment(tenant, "O-1",
                customer, T0, PaymentMethod.SEPAY, Fulfilment.DELIVERY, new Recipient("A", "09", " "), drafts(), TAX,
                UUID.randomUUID(), null, Money.of(0), "h")));
    }

    @Test
    void donKhongDongKhongThanhToanQuaCong() {
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> SalesOrder.placeOnlineAwaitingPayment(tenant, "O-1",
                customer, T0, PaymentMethod.SEPAY, Fulfilment.PICKUP, RECIPIENT,
                List.of(new SalesOrder.LineDraft(UUID.randomUUID(), "S", "I", Money.of(0), 1, null)), TAX,
                UUID.randomUUID(), null, Money.of(0), "h")));
    }

    @Test
    void thanhToanXongSangChoXacNhanDaTraKhongXuatKho() {
        SalesOrder o = awaiting(PaymentMethod.SEPAY, Fulfilment.DELIVERY, 30_000);
        PaymentRecord p = o.settleAwaitingPayment("LMSREF", null, T0.plusSeconds(60));
        assertEquals(OrderStatus.PENDING_CONFIRMATION, o.status());
        assertEquals(PaymentStatus.PAID, o.paymentStatus());
        assertEquals(T0.plusSeconds(60), o.paidAt());
        assertNull(o.completedAt());
        assertNull(o.reservationExpiresAt());
        assertFalse(o.fulfilsStockOnSettle(), "online: tồn vẫn giữ tới khi giao/nhận");
        assertEquals(230_000, p.amount().amount());
        assertEquals(PaymentMethod.SEPAY, p.method());
        assertEquals("LMSREF", p.reference());
        assertEquals(1, o.payments().size());
    }

    @Test
    void donPosVanHoanTatVaXuatKhoKhiThu() {
        SalesOrder pos = SalesOrder.createPosAwaitingPayment(tenant, "P-1", staff, T0, PaymentMethod.QR, null, null,
                null, drafts(), null, TAX, UUID.randomUUID(), null, null);
        assertTrue(pos.fulfilsStockOnSettle());
        pos.settleAwaitingPayment("R", null, T0);
        assertEquals(OrderStatus.COMPLETED, pos.status());
        assertEquals(T0, pos.completedAt());
    }

    @Test
    void khongThuHaiLanVaKhongThuDonDaHuy() {
        SalesOrder o = awaiting(PaymentMethod.SEPAY, Fulfilment.PICKUP, 0);
        o.settleAwaitingPayment("R", null, T0);
        assertEquals(ErrorCode.ORDER_STATE_CONFLICT, codeOf(() -> o.settleAwaitingPayment("R", null, T0)));
        SalesOrder c = awaiting(PaymentMethod.SEPAY, Fulfilment.PICKUP, 0);
        c.cancelAwaitingPayment("Hết hạn", null, T0);
        assertEquals(OrderStatus.CANCELLED, c.status());
        assertEquals(ErrorCode.ORDER_STATE_CONFLICT, codeOf(() -> c.settleAwaitingPayment("R", null, T0)));
    }

    @Test
    void hetHanHuyDonOnlineChoThanhToanChoPhepKhiCoLyDo() {
        SalesOrder o = awaiting(PaymentMethod.SEPAY, Fulfilment.PICKUP, 0);
        assertEquals(ErrorCode.REASON_REQUIRED, codeOf(() -> o.cancelAwaitingPayment(" ", null, T0)));
        o.cancelAwaitingPayment("Hết hạn thanh toán", null, T0);
        assertEquals(OrderStatus.CANCELLED, o.status());
        assertNull(o.refundDueOnCancel(), "chưa thu thì không có khoản hoàn");
    }

    @Test
    void xacNhanDonDaTraKhoaPhiShipKhongTinhLaiTongTien() {
        SalesOrder o = awaiting(PaymentMethod.SEPAY, Fulfilment.DELIVERY, 30_000);
        o.settleAwaitingPayment("R", null, T0);
        assertEquals(ErrorCode.ORDER_STATE_CONFLICT, codeOf(() -> o.confirm(Money.of(50_000), staff, T0)));
        assertEquals(OrderStatus.PENDING_CONFIRMATION, o.status(), "xung đột thì không đổi gì");
        assertEquals(230_000, o.grandTotal().amount());

        o.confirm(Money.of(30_000), staff, T0.plusSeconds(120));
        assertEquals(OrderStatus.CONFIRMED, o.status());
        assertEquals(30_000, o.shippingFee().amount());
        assertEquals(230_000, o.grandTotal().amount());
        assertEquals(staff, o.confirmedBy());
    }

    @Test
    void donDaTraDiTiepMayTrangThaiNhuDonOnlineThuongVaHoanTatDuocVoiPaid() {
        SalesOrder o = awaiting(PaymentMethod.SEPAY, Fulfilment.DELIVERY, 30_000);
        o.settleAwaitingPayment("R", null, T0);
        o.confirm(Money.of(30_000), staff, T0);
        o.markShipping();
        o.complete(T0.plusSeconds(3600));
        assertEquals(OrderStatus.COMPLETED, o.status());
    }

    @Test
    void khachKhongTuHuyDonDaThanhToanNhungNhanVienHuyThiCoKhoanHoan() {
        SalesOrder paid = awaiting(PaymentMethod.SEPAY, Fulfilment.DELIVERY, 30_000);
        paid.settleAwaitingPayment("R", null, T0);
        assertEquals(ErrorCode.ORDER_STATE_CONFLICT,
                codeOf(() -> paid.cancel("Đổi ý", customer, T0, true)));
        assertEquals(OrderStatus.PENDING_CONFIRMATION, paid.status());

        StockEffect effect = paid.cancel("Hết hàng", staff, T0.plusSeconds(10), false);
        assertEquals(StockEffect.RELEASE, effect);
        assertEquals(OrderStatus.CANCELLED, paid.status());
        assertEquals(230_000, paid.refundDueOnCancel().amount());
    }

    @Test
    void khachKhongHuyDonDangChoThanhToanBangLuongHuyDonThuongNhungDonChuaTraCuaKhachVanHuyDuoc() {
        SalesOrder o = awaiting(PaymentMethod.SEPAY, Fulfilment.PICKUP, 0);
        assertEquals(ErrorCode.ORDER_STATE_CONFLICT, codeOf(() -> o.cancel("x", customer, T0, true)));

        SalesOrder cod = SalesOrder.placeOnline(tenant, "O-2", customer, T0, PaymentMethod.COD, Fulfilment.DELIVERY,
                RECIPIENT, drafts(), TAX, UUID.randomUUID());
        assertEquals(StockEffect.RELEASE, cod.cancel("Đổi ý", customer, T0, true));
        assertEquals(OrderStatus.CANCELLED, cod.status());
    }

    @Test
    void markPaidThuCongVanTuChoiCong() {
        SalesOrder o = SalesOrder.placeOnline(tenant, "O-3", customer, T0, PaymentMethod.BANK_TRANSFER,
                Fulfilment.PICKUP, RECIPIENT, drafts(), TAX, UUID.randomUUID());
        o.confirm(Money.ZERO, staff, T0);
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> o.markPaid(PaymentMethod.SEPAY, "r", staff, T0)));
    }

    // ---- P1: giao dịch trước hạn vẫn được nhận dù đồng hồ đã qua hạn ----

    private PaymentIntent intent() {
        return PaymentIntent.create(tenant, UUID.randomUUID(), Money.of(230_000), "LMSABCDEFGHIJ", "SEPAY", "qr", T0,
                T0.plusSeconds(900));
    }

    @Test
    void giaoDichTruocHanVanDuocNhanKhiLenhConPendingDuDongHoQuaHan() {
        PaymentIntent i = intent();
        Instant late = T0.plusSeconds(1000);
        i.markPaid("SEPAY-1", Money.of(230_000), late, T0.plusSeconds(899));
        assertEquals(PaymentIntentStatus.PAID, i.status());
    }

    @Test
    void giaoDichSauHanHoacKhongRoThoiDiemVanBiTuChoi() {
        Instant late = T0.plusSeconds(1000);
        assertEquals(ErrorCode.PAYMENT_INTENT_EXPIRED, codeOf(() -> intent().markPaid("S", Money.of(230_000), late,
                T0.plusSeconds(900))));
        assertEquals(ErrorCode.PAYMENT_INTENT_EXPIRED, codeOf(() -> intent().markPaid("S", Money.of(230_000), late,
                null)));
        assertEquals(ErrorCode.PAYMENT_INTENT_EXPIRED, codeOf(() -> intent().markPaid("S", Money.of(230_000),
                late)));
    }

    @Test
    void lenhDaExpiredTrongDbKhongDuocNhanDuGiaoDichTruocHan() {
        PaymentIntent i = intent();
        i.expire(T0.plusSeconds(1000));
        assertEquals(ErrorCode.PAYMENT_INTENT_EXPIRED, codeOf(() -> i.markPaid("S", Money.of(230_000),
                T0.plusSeconds(1001), T0.plusSeconds(10))));
    }
}
