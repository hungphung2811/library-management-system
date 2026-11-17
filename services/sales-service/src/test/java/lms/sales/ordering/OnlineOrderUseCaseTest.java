package lms.sales.ordering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.sales.ordering.application.OrderResponse;
import lms.sales.ordering.application.PlaceOnlineOrderCommand;
import lms.sales.ordering.application.ShopOrderResponse;
import lms.sales.ordering.domain.Discount;
import lms.sales.ordering.domain.Fulfilment;
import lms.sales.ordering.domain.OrderStatus;
import lms.sales.ordering.domain.PaymentMethod;
import lms.sales.ordering.domain.PaymentStatus;
import lms.sales.ordering.domain.Recipient;
import lms.sales.ordering.domain.RefundStatus;
import lms.sales.settings.application.UpdateSalesSettingsCommand;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.domain.MovementType;
import lms.sales.support.Harness;
import org.junit.jupiter.api.Test;

/** T2.1/T2.2/T2.4: luồng đơn online, giữ/hoàn tồn, thanh toán độc lập, hủy, khách chỉ thấy đơn của mình, VAT và phí ship. */
class OnlineOrderUseCaseTest {

    private final Harness h = new Harness();
    private final UUID customer = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    private PlaceOnlineOrderCommand cmd(UUID who, UUID cid, Fulfilment f, PaymentMethod pm, UUID title, int qty) {
        return new PlaceOnlineOrderCommand(h.tenant, who, List.of(new PlaceOnlineOrderCommand.Line(title, qty)), f,
                pm, new Recipient("Nguyễn Văn An", "0912345678", "12 Lê Lợi, Quận 1, TP HCM"), cid);
    }

    private ShopOrderResponse place(UUID title, int qty, Fulfilment f, PaymentMethod pm) {
        return h.tx(() -> h.placeOnline.place(cmd(customer, UUID.randomUUID(), f, pm, title, qty)));
    }

    private ErrorCode codeOf(Runnable r) {
        return assertThrows(SalesException.class, r::run).code();
    }

    @Test
    void datHangGiuTonNgayKhongTruOnHand() {
        UUID a = h.book("Sách A", 100_000, null, 5);
        ShopOrderResponse o = place(a, 2, Fulfilment.PICKUP, PaymentMethod.BANK_TRANSFER);

        assertEquals(OrderStatus.PENDING_CONFIRMATION, o.order().status());
        assertEquals(PaymentStatus.UNPAID, o.order().paymentStatus());
        assertEquals(0, o.order().shippingFee());
        assertEquals(200_000, o.order().grandTotal());
        assertEquals(5, h.item(a).onHand());
        assertEquals(2, h.item(a).reserved());
        assertEquals(3, h.item(a).available());
        assertEquals(MovementType.RESERVE, h.ledger().get(h.ledger().size() - 1).type());
        assertEquals("Nguyễn Văn An", o.recipient().name()); // khách thấy đầy đủ thông tin của mình
        assertEquals("N***** V** A*", o.order().recipientNameMasked()); // nhân viên chỉ thấy bản che
        assertFalse(o.toString().contains("Nguy"));
    }

    @Test
    void khongDuTonThiTuChoiVaKhongTaoDonMotPhan() {
        UUID a = h.book("Sách A", 100_000, null, 1);
        assertEquals(ErrorCode.INSUFFICIENT_STOCK,
                codeOf(() -> place(a, 2, Fulfilment.PICKUP, PaymentMethod.BANK_TRANSFER)));
        assertEquals(0, h.orderCount());
        assertEquals(0, h.item(a).reserved());
    }

    @Test
    void guiLaiCungClientRequestIdKhongGiuTonLanHai() {
        UUID a = h.book("Sách A", 100_000, null, 5);
        UUID cid = UUID.randomUUID();
        ShopOrderResponse first = h.tx(() -> h.placeOnline.place(cmd(customer, cid, Fulfilment.PICKUP,
                PaymentMethod.CASH, a, 2)));
        ShopOrderResponse again = h.tx(() -> h.placeOnline.place(cmd(customer, cid, Fulfilment.PICKUP,
                PaymentMethod.CASH, a, 2)));
        assertEquals(first.order().id(), again.order().id());
        assertEquals(2, h.item(a).reserved());
        assertEquals(1, h.orderCount());
        // khách khác dùng lại id của người khác thì không lộ đơn
        assertEquals(ErrorCode.CLIENT_REQUEST_CONFLICT, codeOf(() -> h.placeOnline.place(cmd(other, cid,
                Fulfilment.PICKUP, PaymentMethod.CASH, a, 2))));
    }

    @Test
    void donPosGanTaiKhoanKhachKhongLotRaChoKhachVaKhoaCuaPosKhongTraDon() {
        UUID a = h.book("Sách A", 100_000, null, 5);
        UUID posCid = UUID.randomUUID();
        var pos = h.tx(() -> h.placePos.create(new lms.sales.ordering.application.PlacePosOrderCommand(h.tenant,
                h.user, List.of(new lms.sales.ordering.application.PlacePosOrderCommand.Line(a, 1, null)),
                PaymentMethod.CASH, customer, null, null, null, posCid)));
        // khách có customerUserId trùng vẫn không thấy/hủy được đơn POS: 404 (điều kiện kênh ONLINE trong truy vấn)
        assertEquals(ErrorCode.ORDER_NOT_FOUND, codeOf(() -> h.shopOrders.myOrder(h.tenant, customer,
                pos.id())));
        assertEquals(ErrorCode.ORDER_NOT_FOUND, codeOf(() -> h.shopOrders.cancelMyOrder(h.tenant, customer,
                pos.id(), null)));
        assertEquals(0, h.shopOrders.myOrders(h.tenant, customer, 0, 10).total());
        // đặt online bằng khóa đã dùng cho đơn POS: 409 không kèm dữ liệu, kể cả cùng khách
        assertEquals(ErrorCode.CLIENT_REQUEST_CONFLICT, codeOf(() -> h.placeOnline.place(cmd(customer, posCid,
                Fulfilment.PICKUP, PaymentMethod.CASH, a, 1))));
    }

    @Test
    void ketHopHinhThucNhanVaThanhToanKhongHopLeBiTuChoi() {
        UUID a = h.book("Sách A", 100_000, null, 5);
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> place(a, 1, Fulfilment.PICKUP, PaymentMethod.COD)));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> place(a, 1, Fulfilment.DELIVERY, PaymentMethod.CASH)));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> place(a, 1, Fulfilment.DELIVERY, PaymentMethod.CARD)));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> h.placeOnline.place(new PlaceOnlineOrderCommand(
                h.tenant, customer, List.of(new PlaceOnlineOrderCommand.Line(a, 1)), Fulfilment.DELIVERY,
                PaymentMethod.COD, new Recipient("An", "0900000000", null), UUID.randomUUID()))));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> h.placeOnline.place(new PlaceOnlineOrderCommand(
                h.tenant, null, List.of(new PlaceOnlineOrderCommand.Line(a, 1)), Fulfilment.PICKUP,
                PaymentMethod.CASH, new Recipient("An", "0900000000", null), UUID.randomUUID()))));
        assertEquals(0, h.orderCount());
    }

    @Test
    void xacNhanDatPhiShipKhongChiuVatVaMacDinhTheoCauHinh() {
        h.updateSettings.execute(new UpdateSalesSettingsCommand(h.tenant, h.user, 1000, false, 7, 30_000, 5, 12, 0));
        UUID a = h.book("Sách A", 100_000, null, 10); // chưa gồm thuế 10%: line total 110.000
        ShopOrderResponse o = place(a, 1, Fulfilment.DELIVERY, PaymentMethod.COD);
        long vatBefore = o.order().vatTotal();
        assertEquals(10_000, vatBefore);
        assertEquals(110_000, o.order().grandTotal());

        OrderResponse c = h.tx(() -> h.workflow.confirm(h.tenant, h.user, o.order().id(), null, null));
        assertEquals(OrderStatus.CONFIRMED, c.status());
        assertEquals(30_000, c.shippingFee()); // phí cố định của tenant
        assertEquals(140_000, c.grandTotal());
        assertEquals(vatBefore, c.vatTotal()); // phí ship không chịu VAT

        // phí ship nhập tay ở đơn khác
        ShopOrderResponse o2 = place(a, 1, Fulfilment.DELIVERY, PaymentMethod.COD);
        OrderResponse c2 = h.tx(() -> h.workflow.confirm(h.tenant, h.user, o2.order().id(), 15_000L, null));
        assertEquals(125_000, c2.grandTotal());
    }

    @Test
    void nhanTaiCuaHangKhongCoPhiShip() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        ShopOrderResponse o = place(a, 1, Fulfilment.PICKUP, PaymentMethod.CASH);
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> h.workflow.confirm(h.tenant, h.user, o.order().id(),
                5_000L, null)));
        OrderResponse c = h.tx(() -> h.workflow.confirm(h.tenant, h.user, o.order().id(), null, null));
        assertEquals(0, c.shippingFee());
    }

    @Test
    void luongNhanTaiQuayChuyenKhoanChuaThuToiKhiNhanVienDanhDau() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        UUID id = place(a, 2, Fulfilment.PICKUP, PaymentMethod.BANK_TRANSFER).order().id();

        // chưa xác nhận thì chưa đánh dấu thu được
        assertEquals(ErrorCode.ORDER_STATE_CONFLICT, codeOf(() -> h.workflow.markPaid(h.tenant, h.user, id,
                PaymentMethod.BANK_TRANSFER, "FT123", null)));
        h.tx(() -> h.workflow.confirm(h.tenant, h.user, id, null, null));
        h.tx(() -> h.workflow.markReadyForPickup(h.tenant, h.user, id, null));
        assertEquals(PaymentStatus.UNPAID, h.orderQueries.get(h.tenant, id).paymentStatus());
        // hoàn tất cần PAID
        assertEquals(ErrorCode.ORDER_NOT_PAID, codeOf(() -> h.workflow.complete(h.tenant, h.user, id, null)));

        OrderResponse paid = h.tx(() -> h.workflow.markPaid(h.tenant, h.user, id, PaymentMethod.BANK_TRANSFER,
                "FT123", null));
        assertEquals(PaymentStatus.PAID, paid.paymentStatus());
        assertEquals(h.clock.instant(), paid.paidAt());
        var paidAudit = h.audits().stream().filter(x -> x.action().equals(SalesAuditPort.Actions.ORDER_PAID))
                .findFirst().orElseThrow();
        assertEquals(h.user, paidAudit.actorId());
        assertEquals(200_000L, paidAudit.data().get("amount"));
        assertEquals("BANK_TRANSFER", paidAudit.data().get("method"));
        // đánh dấu thu lần hai bị từ chối
        assertEquals(ErrorCode.ORDER_STATE_CONFLICT, codeOf(() -> h.workflow.markPaid(h.tenant, h.user, id,
                PaymentMethod.BANK_TRANSFER, "FT123", null)));

        // nhận tại quầy: tồn chỉ giảm lúc hoàn tất (FULFIL)
        assertEquals(10, h.item(a).onHand());
        assertEquals(2, h.item(a).reserved());
        OrderResponse done = h.tx(() -> h.workflow.complete(h.tenant, h.user, id, null));
        assertEquals(OrderStatus.COMPLETED, done.status());
        assertEquals(8, h.item(a).onHand());
        assertEquals(0, h.item(a).reserved());
        assertTrue(h.reconcile.execute(h.tenant, a).consistent());
    }

    @Test
    void luongGiaoHangCodFulfilLucGiaoVaGiaoThatBaiHoanTon() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        UUID id = place(a, 3, Fulfilment.DELIVERY, PaymentMethod.COD).order().id();
        h.tx(() -> h.workflow.confirm(h.tenant, h.user, id, 0L, null));
        assertEquals(ErrorCode.ORDER_STATE_CONFLICT, codeOf(() -> h.workflow.markReadyForPickup(h.tenant, h.user,
                id, null))); // đơn giao hàng không có bước nhận tại quầy
        h.tx(() -> h.workflow.markShipping(h.tenant, h.user, id, null));
        assertEquals(7, h.item(a).onHand()); // hàng đã rời kho
        assertEquals(0, h.item(a).reserved());

        // giao thất bại: hủy ở SHIPPING hoàn tồn (CANCEL_RESTOCK)
        OrderResponse c = h.tx(() -> h.workflow.cancel(h.tenant, h.user, id, "Giao thất bại", null));
        assertEquals(OrderStatus.CANCELLED, c.status());
        assertEquals(10, h.item(a).onHand());
        assertEquals(0, h.item(a).reserved());
        assertTrue(h.ledger().stream().anyMatch(m -> m.type() == MovementType.CANCEL_RESTOCK));
        assertTrue(h.reconcile.execute(h.tenant, a).consistent());
    }

    @Test
    void codThuKhiGiaoRoiHoanTat() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        UUID id = place(a, 1, Fulfilment.DELIVERY, PaymentMethod.COD).order().id();
        h.tx(() -> h.workflow.confirm(h.tenant, h.user, id, 20_000L, null));
        h.tx(() -> h.workflow.markShipping(h.tenant, h.user, id, null));
        assertEquals(ErrorCode.ORDER_NOT_PAID, codeOf(() -> h.workflow.complete(h.tenant, h.user, id, null)));
        OrderResponse paid = h.tx(() -> h.workflow.markPaid(h.tenant, h.user, id, PaymentMethod.COD, null, null));
        assertEquals(120_000, paid.grandTotal());
        OrderResponse done = h.tx(() -> h.workflow.complete(h.tenant, h.user, id, null));
        assertEquals(OrderStatus.COMPLETED, done.status());
        assertEquals(9, h.item(a).onHand()); // không FULFIL lần hai
        assertEquals(0, h.item(a).reserved());
    }

    @Test
    void khachHuyDonChoXacNhanHoanTonVaKhongSinhHoanTien() {
        UUID a = h.book("Sách A", 100_000, null, 5);
        UUID id = place(a, 2, Fulfilment.PICKUP, PaymentMethod.BANK_TRANSFER).order().id();
        ShopOrderResponse c = h.tx(() -> h.shopOrders.cancelMyOrder(h.tenant, customer, id, null));
        assertEquals(OrderStatus.CANCELLED, c.order().status());
        assertEquals(0, h.item(a).reserved());
        assertEquals(5, h.item(a).onHand());
        assertEquals(0, h.refundRepo.search(h.tenant, null, 0, 10).total());
        // hủy lần hai
        assertEquals(ErrorCode.ORDER_STATE_CONFLICT, codeOf(() -> h.shopOrders.cancelMyOrder(h.tenant, customer, id,
                null)));
    }

    @Test
    void khachKhongHuyDuocDonDaXacNhanVaKhongThaoTacDonNguoiKhac() {
        UUID a = h.book("Sách A", 100_000, null, 5);
        UUID id = place(a, 1, Fulfilment.PICKUP, PaymentMethod.CASH).order().id();
        // người khác: 404, không lộ đơn tồn tại
        assertEquals(ErrorCode.ORDER_NOT_FOUND, codeOf(() -> h.shopOrders.myOrder(h.tenant, other, id)));
        assertEquals(ErrorCode.ORDER_NOT_FOUND, codeOf(() -> h.shopOrders.cancelMyOrder(h.tenant, other, id,
                null)));
        assertEquals(0, h.shopOrders.myOrders(h.tenant, other, 0, 10).total());
        assertEquals(1, h.shopOrders.myOrders(h.tenant, customer, 0, 10).total());
        h.tx(() -> h.workflow.confirm(h.tenant, h.user, id, null, null));
        assertEquals(ErrorCode.ORDER_STATE_CONFLICT, codeOf(() -> h.shopOrders.cancelMyOrder(h.tenant, customer,
                id, null)));
        assertEquals(1, h.item(a).reserved()); // vẫn giữ
    }

    @Test
    void nhanVienHuyDonChuyenKhoanQuaHanChuaThuVaLocTheoThoiGian() {
        UUID a = h.book("Sách A", 100_000, null, 5);
        UUID id = place(a, 2, Fulfilment.PICKUP, PaymentMethod.BANK_TRANSFER).order().id();
        h.clock.advanceSeconds(3 * 86_400);
        var overdue = h.orderQueries.search(h.tenant, new lms.sales.ordering.application.OrderQueryUseCases
                .SearchQuery(null, PaymentStatus.UNPAID, null, null, h.clock.instant().minusSeconds(86_400), null,
                0, 20));
        assertEquals(1, overdue.total());
        h.tx(() -> h.workflow.confirm(h.tenant, h.user, id, null, null));
        OrderResponse c = h.tx(() -> h.workflow.cancel(h.tenant, h.user, id, "Quá hạn chuyển khoản", null));
        assertEquals(OrderStatus.CANCELLED, c.status());
        assertEquals(0, h.item(a).reserved());
        assertEquals(0, h.refundRepo.search(h.tenant, null, 0, 10).total()); // chưa thu thì không hoàn tiền
    }

    @Test
    void huyDonDaThuSinhKhoanHoanChoHoan() {
        UUID a = h.book("Sách A", 100_000, null, 5);
        UUID id = place(a, 1, Fulfilment.PICKUP, PaymentMethod.BANK_TRANSFER).order().id();
        h.tx(() -> h.workflow.confirm(h.tenant, h.user, id, null, null));
        h.tx(() -> h.workflow.markPaid(h.tenant, h.user, id, PaymentMethod.BANK_TRANSFER, "FT1", null));
        assertEquals(ErrorCode.REASON_REQUIRED, codeOf(() -> h.workflow.cancel(h.tenant, h.user, id, " ", null)));
        OrderResponse c = h.tx(() -> h.workflow.cancel(h.tenant, h.user, id, "Khách đổi ý", null));
        assertEquals(OrderStatus.CANCELLED, c.status());
        assertEquals(PaymentStatus.PAID, c.paymentStatus());
        var refunds = h.refundRepo.search(h.tenant, RefundStatus.PENDING, 0, 10);
        assertEquals(1, refunds.total());
        assertEquals(100_000, refunds.items().get(0).amount().amount());

        var r = h.tx(() -> h.refundUseCases.markRefunded(h.tenant, h.user, refunds.items().get(0).id(),
                PaymentMethod.BANK_TRANSFER, "RF1", null));
        assertEquals(RefundStatus.REFUNDED, r.status());
        assertEquals(PaymentStatus.REFUNDED, h.orderQueries.get(h.tenant, id).paymentStatus());
        // đánh dấu hoàn lần hai bị từ chối
        assertEquals(ErrorCode.ORDER_STATE_CONFLICT, codeOf(() -> h.refundUseCases.markRefunded(h.tenant, h.user,
                r.id(), PaymentMethod.CASH, null, null)));
    }

    @Test
    void phienBanLechBiTuChoi() {
        UUID a = h.book("Sách A", 100_000, null, 5);
        UUID id = place(a, 1, Fulfilment.PICKUP, PaymentMethod.CASH).order().id();
        assertEquals(ErrorCode.ORDER_STATE_CONFLICT, codeOf(() -> h.workflow.confirm(h.tenant, h.user, id, null,
                99L)));
        assertEquals(ErrorCode.ORDER_NOT_FOUND, codeOf(() -> h.workflow.confirm(h.tenant, h.user, UUID.randomUUID(),
                null, null)));
    }

    @Test
    void giamGiaDonOnlineTinhLaiRoiChanKhiDaThu() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        UUID id = place(a, 2, Fulfilment.PICKUP, PaymentMethod.BANK_TRANSFER).order().id();
        OrderResponse d = h.tx(() -> h.workflow.applyDiscount(h.tenant, h.user, id, Discount.percent(1000),
                Map.of(), null));
        assertEquals(20_000, d.discountTotal());
        assertEquals(180_000, d.grandTotal());
        assertEquals(180_000, d.lines().get(0).lineTotal());
        assertEquals(ErrorCode.DISCOUNT_EXCEEDS_AMOUNT, codeOf(() -> h.workflow.applyDiscount(h.tenant, h.user, id,
                Discount.amount(300_000), Map.of(), null)));
        // gỡ giảm giá
        OrderResponse none = h.tx(() -> h.workflow.applyDiscount(h.tenant, h.user, id, null, Map.of(), null));
        assertEquals(200_000, none.grandTotal());

        h.tx(() -> h.workflow.confirm(h.tenant, h.user, id, null, null));
        h.tx(() -> h.workflow.markPaid(h.tenant, h.user, id, PaymentMethod.BANK_TRANSFER, null, null));
        assertEquals(ErrorCode.ORDER_STATE_CONFLICT, codeOf(() -> h.workflow.applyDiscount(h.tenant, h.user, id,
                Discount.percent(500), Map.of(), null)));
    }

    @Test
    void doiCauHinhSauDoKhongDoiDonCu() {
        UUID a = h.book("Sách A", 105_000, null, 10); // mặc định: 5% đã gồm thuế
        UUID id = place(a, 1, Fulfilment.PICKUP, PaymentMethod.CASH).order().id();
        long vat = h.orderQueries.get(h.tenant, id).vatTotal();
        assertEquals(5_000, vat);
        h.updateSettings.execute(new UpdateSalesSettingsCommand(h.tenant, h.user, 1000, false, 7, 0, 5, 12, 0));
        OrderResponse o = h.orderQueries.get(h.tenant, id);
        assertEquals(500, o.vatRateBps());
        assertTrue(o.pricesIncludeVat());
        assertEquals(5_000, o.vatTotal());
        assertEquals(105_000, o.grandTotal());
        assertNotEquals(1000, o.vatRateBps());
        assertNull(o.cancelledAt());
    }
}
