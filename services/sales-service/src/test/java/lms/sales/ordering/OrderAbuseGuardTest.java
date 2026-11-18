package lms.sales.ordering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lms.sales.ordering.application.PlaceOnlineOrderCommand;
import lms.sales.ordering.application.ShopOrderResponse;
import lms.sales.ordering.domain.Fulfilment;
import lms.sales.ordering.domain.OrderStatus;
import lms.sales.ordering.domain.PaymentMethod;
import lms.sales.ordering.domain.Recipient;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.domain.MovementType;
import lms.sales.support.Harness;
import org.junit.jupiter.api.Test;

/**
 * Chống lạm dụng đặt hàng online (docs/security/ORDER_ABUSE_AUDIT.md, pha 1 đến 3): hạn giữ hàng và worker hết hạn
 * (nhả kho đúng một lần), trần số đơn chờ/số đơn trong ngày/số lượng theo tenant, và idempotency so khớp nội dung.
 */
class OrderAbuseGuardTest {

    private final Harness h = new Harness();
    private final UUID customer = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    private PlaceOnlineOrderCommand cmd(UUID who, UUID cid, UUID title, int qty) {
        return cmd(who, cid, title, qty, "Nguyễn Văn An");
    }

    private PlaceOnlineOrderCommand cmd(UUID who, UUID cid, UUID title, int qty, String recipientName) {
        return new PlaceOnlineOrderCommand(h.tenant, who, List.of(new PlaceOnlineOrderCommand.Line(title, qty)),
                Fulfilment.PICKUP, PaymentMethod.CASH, new Recipient(recipientName, "0912345678", null), cid);
    }

    private ShopOrderResponse place(UUID who, UUID title, int qty) {
        return h.tx(() -> h.placeOnline.place(cmd(who, UUID.randomUUID(), title, qty)));
    }

    private ErrorCode codeOf(Runnable r) {
        return assertThrows(SalesException.class, r::run).code();
    }

    private void setPolicy(int minutes, int pending, int perDay, int perLine, int total) {
        h.setAbusePolicy(minutes, pending, perDay, perLine, total);
    }

    private long releases() {
        return h.ledger().stream().filter(m -> m.type() == MovementType.RELEASE).count();
    }

    // ===== Hạn giữ hàng và worker =====

    @Test
    void donOnlineMoiCoHanGiuHangMacDinh15Phut() {
        UUID a = h.book("Sách A", 100_000, null, 5);
        ShopOrderResponse o = place(customer, a, 2);

        var saved = h.orderRepo.findById(h.tenant, o.order().id()).orElseThrow();
        assertEquals(h.clock.instant().plus(Duration.ofMinutes(15)), saved.reservationExpiresAt());
    }

    @Test
    void quaHanThiHuyDonVaNhaKhoDungMotLan() {
        UUID a = h.book("Sách A", 100_000, null, 5);
        ShopOrderResponse o = place(customer, a, 3);
        assertEquals(3, h.item(a).reserved());

        h.clock.advance(Duration.ofMinutes(16));
        assertEquals(1, h.tx(() -> h.expireReservations.execute(h.tenant)));

        assertEquals(OrderStatus.CANCELLED, h.orderRepo.findById(h.tenant, o.order().id()).orElseThrow().status());
        assertEquals(0, h.item(a).reserved());
        assertEquals(5, h.item(a).onHand());
        assertEquals(1, releases());
        // Chạy lại và gọi thẳng từng đơn: không nhả thêm lần nào
        assertEquals(0, h.tx(() -> h.expireReservations.execute(h.tenant)));
        assertEquals(false, h.tx(() -> h.reservationExpiry.expireOne(h.tenant, o.order().id())));
        assertEquals(1, releases());
        assertEquals(0, h.item(a).reserved());
    }

    @Test
    void chuaToiHanThiKhongDong() {
        UUID a = h.book("Sách A", 100_000, null, 5);
        ShopOrderResponse o = place(customer, a, 2);

        h.clock.advance(Duration.ofMinutes(14));

        assertEquals(0, h.tx(() -> h.expireReservations.execute(h.tenant)));
        assertEquals(OrderStatus.PENDING_CONFIRMATION,
                h.orderRepo.findById(h.tenant, o.order().id()).orElseThrow().status());
        assertEquals(2, h.item(a).reserved());
    }

    @Test
    void donDaXacNhanKhongBiHetHan() {
        UUID a = h.book("Sách A", 100_000, null, 5);
        ShopOrderResponse o = place(customer, a, 2);
        h.tx(() -> h.workflow.confirm(h.tenant, h.user, o.order().id(), null, null));
        assertNull(h.orderRepo.findById(h.tenant, o.order().id()).orElseThrow().reservationExpiresAt());

        h.clock.advance(Duration.ofHours(2));

        assertEquals(0, h.tx(() -> h.expireReservations.execute(h.tenant)));
        assertEquals(OrderStatus.CONFIRMED, h.orderRepo.findById(h.tenant, o.order().id()).orElseThrow().status());
        assertEquals(2, h.item(a).reserved());
        assertEquals(0, releases());
    }

    @Test
    void khachDaHuyTruocThiWorkerKhongNhaKhoLanNua() {
        UUID a = h.book("Sách A", 100_000, null, 5);
        ShopOrderResponse o = place(customer, a, 2);
        h.tx(() -> h.shopOrders.cancelMyOrder(h.tenant, customer, o.order().id(), "Đổi ý"));
        assertEquals(1, releases());

        h.clock.advance(Duration.ofHours(1));

        assertEquals(0, h.tx(() -> h.expireReservations.execute(h.tenant)));
        assertEquals(1, releases());
        assertEquals(0, h.item(a).reserved());
    }

    @Test
    void hanKhongPhutThiKhongBaoGioHetHan() {
        setPolicy(0, 5, 20, 50, 100);
        UUID a = h.book("Sách A", 100_000, null, 5);
        ShopOrderResponse o = place(customer, a, 2);
        assertNull(h.orderRepo.findById(h.tenant, o.order().id()).orElseThrow().reservationExpiresAt());

        h.clock.advance(Duration.ofDays(30));

        assertEquals(0, h.tx(() -> h.expireReservations.execute(h.tenant)));
        assertEquals(2, h.item(a).reserved());
    }

    @Test
    void hetHanXongKhachDatLaiDuocHangDaTraVeKho() {
        UUID a = h.book("Sách A", 100_000, null, 2);
        place(customer, a, 2);
        assertEquals(ErrorCode.INSUFFICIENT_STOCK, codeOf(() -> place(other, a, 1)));

        h.clock.advance(Duration.ofMinutes(20));
        h.tx(() -> h.expireReservations.execute(h.tenant));

        assertEquals(OrderStatus.PENDING_CONFIRMATION, place(other, a, 2).order().status());
    }

    // ===== Trần theo khách =====

    @Test
    void vuotTranDonChoThiTuChoiVaHuyBotThiDatDuoc() {
        setPolicy(15, 2, 20, 50, 100);
        UUID a = h.book("Sách A", 100_000, null, 50);
        place(customer, a, 1);
        ShopOrderResponse second = place(customer, a, 1);

        assertEquals(ErrorCode.ORDER_LIMIT_EXCEEDED, codeOf(() -> place(customer, a, 1)));
        assertEquals(2, h.item(a).reserved()); // đơn bị từ chối không giữ hàng
        // khách khác không bị ảnh hưởng
        place(other, a, 1);

        h.tx(() -> h.shopOrders.cancelMyOrder(h.tenant, customer, second.order().id(), "Đổi ý"));
        place(customer, a, 1);
    }

    @Test
    void vuotTranDonTrongNgayTinhCaDonDaHuyVaTuGiaiPhongSau24Gio() {
        setPolicy(15, 10, 3, 50, 100);
        UUID a = h.book("Sách A", 100_000, null, 50);
        for (int i = 0; i < 3; i++) {
            ShopOrderResponse o = place(customer, a, 1);
            h.tx(() -> h.shopOrders.cancelMyOrder(h.tenant, customer, o.order().id(), "Đổi ý"));
        }

        assertEquals(ErrorCode.ORDER_LIMIT_EXCEEDED, codeOf(() -> place(customer, a, 1)));

        h.clock.advance(Duration.ofHours(25));
        place(customer, a, 1);
    }

    @Test
    void vuotTranSoLuongMoiDongHoacMoiDon() {
        setPolicy(15, 5, 20, 5, 8);
        UUID a = h.book("Sách A", 100_000, null, 100);
        UUID b = h.book("Sách B", 100_000, null, 100);

        assertEquals(ErrorCode.INVALID_QUANTITY, codeOf(() -> place(customer, a, 6)));
        var twoLines = new PlaceOnlineOrderCommand(h.tenant, customer, List.of(
                new PlaceOnlineOrderCommand.Line(a, 5), new PlaceOnlineOrderCommand.Line(b, 5)),
                Fulfilment.PICKUP, PaymentMethod.CASH, new Recipient("An", "0912345678", null), UUID.randomUUID());
        assertEquals(ErrorCode.INVALID_QUANTITY, codeOf(() -> h.placeOnline.place(twoLines)));
        assertEquals(0, h.orderCount());
        assertEquals(0, h.item(a).reserved());

        place(customer, a, 5); // đúng trần thì được
    }

    // ===== Idempotency so khớp nội dung =====

    @Test
    void cungKhoaCungNoiDungTraDonCuNhungKhacNoiDungBiTuChoi() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        UUID cid = UUID.randomUUID();
        ShopOrderResponse first = h.tx(() -> h.placeOnline.place(cmd(customer, cid, a, 2)));

        ShopOrderResponse again = h.tx(() -> h.placeOnline.place(cmd(customer, cid, a, 2)));
        assertEquals(first.order().id(), again.order().id());

        assertEquals(ErrorCode.IDEMPOTENCY_KEY_REUSED, codeOf(() -> h.placeOnline.place(cmd(customer, cid, a, 3))));
        assertEquals(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                codeOf(() -> h.placeOnline.place(cmd(customer, cid, a, 2, "Người nhận khác"))));
        assertEquals(1, h.orderCount());
        assertEquals(2, h.item(a).reserved()); // chỉ một lần giữ hàng
    }

    @Test
    void thuTuDongVaKhoangTrangKhongLamDoiNoiDung() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        UUID b = h.book("Sách B", 100_000, null, 10);
        UUID cid = UUID.randomUUID();
        var ab = new PlaceOnlineOrderCommand(h.tenant, customer, List.of(new PlaceOnlineOrderCommand.Line(a, 1),
                new PlaceOnlineOrderCommand.Line(b, 2)), Fulfilment.PICKUP, PaymentMethod.CASH,
                new Recipient("An", "0912345678", null), cid);
        var ba = new PlaceOnlineOrderCommand(h.tenant, customer, List.of(new PlaceOnlineOrderCommand.Line(b, 2),
                new PlaceOnlineOrderCommand.Line(a, 1)), Fulfilment.PICKUP, PaymentMethod.CASH,
                new Recipient("  An ", "0912345678", null), cid);

        ShopOrderResponse first = h.tx(() -> h.placeOnline.place(ab));
        ShopOrderResponse again = h.tx(() -> h.placeOnline.place(ba));

        assertEquals(first.order().id(), again.order().id());
        assertTrue(h.orderCount() == 1);
    }

    @Test
    void khoaCuaKhachKhacVanLaXungDotKhongLoDon() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        UUID cid = UUID.randomUUID();
        h.tx(() -> h.placeOnline.place(cmd(customer, cid, a, 2)));

        assertEquals(ErrorCode.CLIENT_REQUEST_CONFLICT, codeOf(() -> h.placeOnline.place(cmd(other, cid, a, 2))));
    }
}
