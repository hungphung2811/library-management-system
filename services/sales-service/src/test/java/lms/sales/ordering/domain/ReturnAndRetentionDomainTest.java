package lms.sales.ordering.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.PersonalDataMasker;
import lms.sales.shared.domain.RetentionPolicy;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.Rate;
import lms.sales.shared.domain.SalesException;
import org.junit.jupiter.api.Test;

/** Domain thuần: máy trạng thái đơn online, ReturnPlanner (cửa sổ, tiền hoàn), RetentionPolicy, che địa chỉ. */
class ReturnAndRetentionDomainTest {

    private static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");
    private static final PriceCalculator.TaxPolicy TAX = new PriceCalculator.TaxPolicy(Rate.ofBps(500), true);

    private static SalesOrder.LineDraft draft(long price, int qty) {
        return new SalesOrder.LineDraft(UUID.randomUUID(), "Sách", "ISBN", Money.of(price), qty, null);
    }

    private static SalesOrder pos(long price, int qty) {
        return SalesOrder.createPos(UUID.randomUUID(), "DH-1", UUID.randomUUID(), T0, PaymentMethod.CASH, null,
                null, null, List.of(draft(price, qty)), null, TAX, UUID.randomUUID());
    }

    private static SalesOrder online(Fulfilment f, PaymentMethod m) {
        return SalesOrder.placeOnline(UUID.randomUUID(), "DH-2", UUID.randomUUID(), T0, m, f,
                new Recipient("An", "0900000000", "1 A, Quận 1, HCM"), List.of(draft(100_000, 2)), TAX,
                UUID.randomUUID());
    }

    private static ErrorCode codeOf(Runnable r) {
        return assertThrows(SalesException.class, r::run).code();
    }

    // ---- máy trạng thái ----

    @Test
    void donOnlineMoiLaChoXacNhanChuaThuVaKhongPhiShip() {
        SalesOrder o = online(Fulfilment.DELIVERY, PaymentMethod.COD);
        assertEquals(OrderStatus.PENDING_CONFIRMATION, o.status());
        assertEquals(PaymentStatus.UNPAID, o.paymentStatus());
        assertEquals(0, o.shippingFee().amount());
        assertTrue(o.payments().isEmpty());
        assertNull(o.paidAt());
    }

    @Test
    void trucThanhToanDocLapVaCacChuyenKhongHopLeBiTuChoi() {
        SalesOrder o = online(Fulfilment.PICKUP, PaymentMethod.BANK_TRANSFER);
        assertEquals(ErrorCode.ORDER_STATE_CONFLICT, codeOf(() -> o.markPaid(PaymentMethod.CASH, null, null, T0)));
        assertEquals(ErrorCode.ORDER_STATE_CONFLICT, codeOf(() -> o.markReadyForPickup()));
        assertEquals(ErrorCode.ORDER_STATE_CONFLICT, codeOf(() -> o.complete(T0)));
        o.confirm(Money.ZERO, null, T0);
        assertEquals(ErrorCode.ORDER_STATE_CONFLICT, codeOf(() -> o.confirm(Money.ZERO, null, T0)));
        assertEquals(ErrorCode.ORDER_STATE_CONFLICT, codeOf(() -> o.markShipping())); // PICKUP không giao
        o.markReadyForPickup();
        assertEquals(ErrorCode.ORDER_NOT_PAID, codeOf(() -> o.complete(T0)));
        var p = o.markPaid(PaymentMethod.BANK_TRANSFER, " FT1 ", UUID.randomUUID(), T0);
        assertEquals("FT1", p.reference());
        assertEquals(o.grandTotal(), p.amount());
        o.complete(T0.plusSeconds(60));
        assertEquals(OrderStatus.COMPLETED, o.status());
        assertEquals(ErrorCode.ORDER_STATE_CONFLICT, codeOf(() -> o.cancel("x", null, T0, false))); // đã hoàn tất
    }

    @Test
    void huyTheoTrangThaiVaHeQuaTonKho() {
        SalesOrder pending = online(Fulfilment.PICKUP, PaymentMethod.CASH);
        assertEquals(StockEffect.RELEASE, pending.cancel("lý do", null, T0, true));
        assertEquals(OrderStatus.CANCELLED, pending.status());
        assertNull(pending.refundDueOnCancel()); // chưa thu

        SalesOrder confirmed = online(Fulfilment.DELIVERY, PaymentMethod.COD);
        confirmed.confirm(Money.of(10_000), null, T0);
        assertEquals(ErrorCode.ORDER_STATE_CONFLICT, codeOf(() -> confirmed.cancel("x", null, T0, true))); // khách
        confirmed.markShipping();
        confirmed.markPaid(PaymentMethod.COD, null, null, T0);
        assertEquals(StockEffect.RESTOCK, confirmed.cancel("Giao thất bại", null, T0, false));
        assertEquals(210_000 - 10_000 + 10_000, confirmed.refundDueOnCancel().amount()); // hoàn cả phí ship
        assertEquals(ErrorCode.REASON_REQUIRED, codeOf(() -> online(Fulfilment.PICKUP, PaymentMethod.CASH)
                .cancel(" ", null, T0, false)));
        assertEquals(ErrorCode.ORDER_STATE_CONFLICT, codeOf(() -> pos(1000, 1).cancel("x", null, T0, false)));
    }

    @Test
    void giamGiaDonOnlineKhongLamTongAm() {
        SalesOrder o = online(Fulfilment.PICKUP, PaymentMethod.CASH);
        o.applyDiscount(Discount.amount(200_000), Map.of());
        assertEquals(0, o.grandTotal().amount());
        assertEquals(ErrorCode.DISCOUNT_EXCEEDS_AMOUNT, codeOf(() -> o.applyDiscount(Discount.amount(200_001),
                Map.of())));
        o.applyDiscount(null, Map.of(1, Discount.percent(5000)));
        assertEquals(100_000, o.grandTotal().amount());
    }

    @Test
    void toStringKhongInDuLieuCaNhan() {
        SalesOrder o = online(Fulfilment.DELIVERY, PaymentMethod.COD);
        assertFalse(o.toString().contains("0900000000"));
        assertFalse(o.recipient().toString().contains("An"));
    }

    // ---- ReturnPlanner ----

    private static SalesOrder completedPos(long price, int qty) {
        return pos(price, qty); // COMPLETED + PAID lúc T0
    }

    private static ReturnPlanner.RequestLine req(SalesOrder o, int qty, Disposition d) {
        return new ReturnPlanner.RequestLine(o.lines().get(0).id(), qty, d);
    }

    @Test
    void cuaSoTraTinhTuNgayHoanTatVaNgayQuaHanLamTronLen() {
        SalesOrder o = completedPos(100_000, 1);
        Instant deadline = ReturnPlanner.deadline(T0, 7);
        assertEquals(T0.plusSeconds(7 * 86_400), deadline);
        assertEquals(0, ReturnPlanner.daysOverdue(deadline, deadline));
        assertEquals(1, ReturnPlanner.daysOverdue(deadline, deadline.plusSeconds(1)));
        assertEquals(2, ReturnPlanner.daysOverdue(deadline, deadline.plusSeconds(86_401)));

        var ok = ReturnPlanner.plan(o, Map.of(), List.of(req(o, 1, Disposition.RESTOCK)), 7, false, null, deadline);
        assertFalse(ok.late());
        var e = assertThrows(SalesException.class, () -> ReturnPlanner.plan(o, Map.of(),
                List.of(req(o, 1, Disposition.RESTOCK)), 7, false, null, deadline.plusSeconds(1)));
        assertEquals(ErrorCode.RETURN_WINDOW_EXPIRED, e.code());
        var late = ReturnPlanner.plan(o, Map.of(), List.of(req(o, 1, Disposition.RESTOCK)), 7, true, "lý do",
                deadline.plusSeconds(1));
        assertTrue(late.late());
        assertEquals(1, late.daysOverdue());
        assertEquals(ErrorCode.LATE_REASON_REQUIRED, codeOf(() -> ReturnPlanner.plan(o, Map.of(),
                List.of(req(o, 1, Disposition.RESTOCK)), 7, true, "  ", deadline.plusSeconds(1))));
    }

    @Test
    void tienHoanTheoDongPhanDuDonVaoLanTraCuoiKhongVuotTongDong() {
        SalesOrder o = completedPos(10_000, 3); // line total 30.000 gồm thuế
        long total = o.lines().get(0).lineTotal().amount();
        UUID lineId = o.lines().get(0).id();
        // lần 1: 1/3 = 10.000
        var p1 = ReturnPlanner.plan(o, Map.of(), List.of(req(o, 1, Disposition.RESTOCK)), 7, false, null, T0);
        assertEquals(10_000, p1.refundTotal().amount());
        // lần 2: đã trả 1 và hoàn 10.000, trả tiếp 1
        var p2 = ReturnPlanner.plan(o, Map.of(lineId, new ReturnPlanner.Prior(1, 10_000)),
                List.of(req(o, 1, Disposition.WRITE_OFF)), 7, false, null, T0);
        assertEquals(10_000, p2.refundTotal().amount());
        // lần cuối nhận phần còn lại
        var p3 = ReturnPlanner.plan(o, Map.of(lineId, new ReturnPlanner.Prior(2, 20_001)),
                List.of(req(o, 1, Disposition.RESTOCK)), 7, false, null, T0);
        assertEquals(total - 20_001, p3.refundTotal().amount());
        // vượt số đã mua (tính cả lần trước)
        assertEquals(ErrorCode.RETURN_QUANTITY_EXCEEDED, codeOf(() -> ReturnPlanner.plan(o,
                Map.of(lineId, new ReturnPlanner.Prior(2, 20_000)), List.of(req(o, 2, Disposition.RESTOCK)), 7,
                false, null, T0)));
    }

    @Test
    void phieuTraKhongHopLeBiTuChoi() {
        SalesOrder o = completedPos(10_000, 2);
        UUID lineId = o.lines().get(0).id();
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> ReturnPlanner.plan(o, Map.of(), List.of(), 7, false,
                null, T0)));
        assertEquals(ErrorCode.INVALID_QUANTITY, codeOf(() -> ReturnPlanner.plan(o, Map.of(),
                List.of(new ReturnPlanner.RequestLine(lineId, 0, Disposition.RESTOCK)), 7, false, null, T0)));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> ReturnPlanner.plan(o, Map.of(),
                List.of(new ReturnPlanner.RequestLine(UUID.randomUUID(), 1, Disposition.RESTOCK)), 7, false, null,
                T0)));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> ReturnPlanner.plan(o, Map.of(),
                List.of(req(o, 1, Disposition.RESTOCK), req(o, 1, Disposition.RESTOCK)), 7, false, null, T0)));
        SalesOrder pending = online(Fulfilment.PICKUP, PaymentMethod.CASH);
        assertEquals(ErrorCode.ORDER_STATE_CONFLICT, codeOf(() -> ReturnPlanner.plan(pending, Map.of(),
                List.of(new ReturnPlanner.RequestLine(pending.lines().get(0).id(), 1, Disposition.RESTOCK)), 7,
                false, null, T0)));
    }

    // ---- Refund, RetentionPolicy, che địa chỉ ----

    @Test
    void khoanHoanChiDanhDauMotLanVaPhaiLonHonKhong() {
        Refund r = Refund.forCancellation(UUID.randomUUID(), UUID.randomUUID(), Money.of(5_000), T0);
        assertEquals(RefundStatus.PENDING, r.status());
        r.markRefunded(PaymentMethod.CASH, " ", UUID.randomUUID(), T0);
        assertEquals(RefundStatus.REFUNDED, r.status());
        assertNull(r.reference());
        assertEquals(ErrorCode.ORDER_STATE_CONFLICT, codeOf(() -> r.markRefunded(PaymentMethod.CASH, null, null,
                T0)));
        assertThrows(SalesException.class, () -> Refund.forCancellation(UUID.randomUUID(), UUID.randomUUID(),
                Money.ZERO, T0));
    }

    @Test
    void motCuaTinhTheoThangVaGioiHan() {
        Instant now = Instant.parse("2026-11-17T03:00:00Z");
        assertEquals(Instant.parse("2025-11-17T03:00:00Z"), RetentionPolicy.cutoff(now, 12));
        assertEquals(Instant.parse("2026-10-17T03:00:00Z"), RetentionPolicy.cutoff(now, 1));
        assertEquals(Instant.parse("2016-11-17T03:00:00Z"), RetentionPolicy.cutoff(now, 120));
        assertThrows(SalesException.class, () -> RetentionPolicy.cutoff(now, 0));
        assertThrows(SalesException.class, () -> RetentionPolicy.cutoff(now, 121));
    }

    @Test
    void cheDiaChiChiGiuQuanHuyenVaTinh() {
        assertEquals("***, Quận 1, TP HCM", PersonalDataMasker.maskAddress("12 Lê Lợi, Quận 1, TP HCM"));
        assertEquals("***, Quận 1, TP HCM", PersonalDataMasker.maskAddress("12 Lê Lợi, P. Bến Nghé, Quận 1, TP HCM"
                .replace("P. Bến Nghé, ", "")));
        assertEquals("***", PersonalDataMasker.maskAddress("12 Lê Lợi"));
        assertNull(PersonalDataMasker.maskAddress(" "));
    }
}
