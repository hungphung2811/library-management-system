package lms.sales.ordering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import lms.sales.ordering.application.CreateReturnCommand;
import lms.sales.ordering.application.OrderResponse;
import lms.sales.ordering.application.PlacePosOrderCommand;
import lms.sales.ordering.application.ReturnResponse;
import lms.sales.ordering.domain.Disposition;
import lms.sales.ordering.domain.PaymentMethod;
import lms.sales.ordering.domain.PaymentStatus;
import lms.sales.ordering.domain.RefundStatus;
import lms.sales.settings.application.UpdateSalesSettingsCommand;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.domain.MovementType;
import lms.sales.support.Harness;
import org.junit.jupiter.api.Test;

/** T2.3: trả hàng trong N ngày, quá N ngày (create/createLate), RESTOCK/WRITE_OFF, vượt số đã mua, tiền hoàn. */
class ReturnUseCaseTest {

    private final Harness h = new Harness();

    private OrderResponse posOrder(UUID title, int qty) {
        return h.tx(() -> h.placePos.create(new PlacePosOrderCommand(h.tenant, h.user,
                List.of(new PlacePosOrderCommand.Line(title, qty, null)), PaymentMethod.CASH, null, null, null, null,
                UUID.randomUUID())));
    }

    private CreateReturnCommand ret(OrderResponse o, int qty, Disposition d, String lateReason) {
        return new CreateReturnCommand(h.tenant, h.user, o.id(), List.of(new CreateReturnCommand.Line(
                o.lines().get(0).id(), qty, d)), "Sách lỗi", lateReason, UUID.randomUUID());
    }

    private ErrorCode codeOf(Runnable r) {
        return assertThrows(SalesException.class, r::run).code();
    }

    @Test
    void traTrongHanNhapLaiTonVaSinhKhoanHoanChoHoan() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        OrderResponse o = posOrder(a, 3);
        assertEquals(7, h.item(a).onHand());

        ReturnResponse r = h.tx(() -> h.createReturn.create(ret(o, 2, Disposition.RESTOCK, null)));
        assertEquals(9, h.item(a).onHand());
        assertEquals(200_000, r.refundTotal());
        assertFalse(r.late());
        assertEquals(MovementType.RETURN, h.ledger().get(h.ledger().size() - 1).type());
        var refunds = h.refundRepo.search(h.tenant, RefundStatus.PENDING, 0, 10);
        assertEquals(1, refunds.total());
        assertEquals(r.refundId(), refunds.items().get(0).id());
        assertEquals(1, h.lockedOrders.size()); // đã khóa dòng đơn trước khi tính
        assertTrue(h.reconcile.execute(h.tenant, a).consistent());
        // thanh toán trục riêng đổi khi hoàn tiền xong
        h.tx(() -> h.refundUseCases.markRefunded(h.tenant, h.user, r.refundId(), PaymentMethod.CASH, null, null));
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, h.orderQueries.get(h.tenant, o.id()).paymentStatus());
    }

    @Test
    void traHangHongGhiHaoHutTonKhongDoiNhungSoCaiThayRo() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        OrderResponse o = posOrder(a, 2);
        int before = h.ledger().size();
        h.tx(() -> h.createReturn.create(ret(o, 1, Disposition.WRITE_OFF, null)));
        assertEquals(8, h.item(a).onHand()); // RETURN +1 rồi WRITE_OFF -1
        var added = h.ledger().subList(before, h.ledger().size());
        assertEquals(List.of(MovementType.RETURN, MovementType.WRITE_OFF), added.stream().map(m -> m.type()).toList());
        assertTrue(added.get(1).reason().contains("Sách lỗi"));
        assertTrue(h.reconcile.execute(h.tenant, a).consistent());
    }

    @Test
    void vuotSoDaMuaTinhCaLanTruBiTuChoiVaKhongGhiGi() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        OrderResponse o = posOrder(a, 3);
        h.tx(() -> h.createReturn.create(ret(o, 2, Disposition.RESTOCK, null)));
        int ledgerSize = h.ledger().size();
        assertEquals(ErrorCode.RETURN_QUANTITY_EXCEEDED, codeOf(() -> h.tx(() -> h.createReturn.create(
                ret(o, 2, Disposition.RESTOCK, null)))));
        assertEquals(ledgerSize, h.ledger().size());
        // phần còn lại (1) trả được, nhận phần dư làm tròn để tổng hoàn = tổng dòng
        ReturnResponse last = h.tx(() -> h.createReturn.create(ret(o, 1, Disposition.RESTOCK, null)));
        assertEquals(100_000, last.refundTotal());
        assertEquals(ErrorCode.RETURN_QUANTITY_EXCEEDED, codeOf(() -> h.tx(() -> h.createReturn.create(
                ret(o, 1, Disposition.RESTOCK, null)))));
        long refunded = h.refundRepo.search(h.tenant, null, 0, 10).items().stream()
                .mapToLong(x -> x.amount().amount()).sum();
        assertEquals(300_000, refunded); // không vượt tổng dòng
    }

    @Test
    void quaHanBiTuChoiOCreateNhungCreateLateCoLyDoThiThanhCongVaCoAudit() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        OrderResponse o = posOrder(a, 1);
        h.clock.advanceSeconds(8L * 86_400); // N = 7

        var e = assertThrows(SalesException.class, () -> h.tx(() -> h.createReturn.create(
                ret(o, 1, Disposition.RESTOCK, null))));
        assertEquals(ErrorCode.RETURN_WINDOW_EXPIRED, e.code());

        // thiếu lý do: 400 LATE_REASON_REQUIRED
        assertEquals(ErrorCode.LATE_REASON_REQUIRED, codeOf(() -> h.createReturn.createLate(
                ret(o, 1, Disposition.RESTOCK, " "))));
        assertEquals(ErrorCode.LATE_REASON_REQUIRED, codeOf(() -> h.createReturn.createLate(
                ret(o, 1, Disposition.RESTOCK, null))));

        ReturnResponse r = h.tx(() -> h.createReturn.createLate(ret(o, 1, Disposition.RESTOCK,
                "Khách ở xa, quản lý đồng ý")));
        assertTrue(r.late());
        assertEquals("Khách ở xa, quản lý đồng ý", r.lateReason());
        assertEquals(1, r.daysOverdue()); // 8 ngày - 7 ngày, tối thiểu 1
        assertEquals(h.user, r.createdBy());
        var audit = h.audits().stream().filter(x -> x.action().equals(SalesAuditPort.Actions.RETURN_LATE_APPROVED))
                .findFirst().orElseThrow();
        assertEquals(h.user, audit.actorId());
        assertEquals(1, audit.data().get("daysOverdue"));
        assertEquals("Khách ở xa, quản lý đồng ý", audit.data().get("lateReason"));
    }

    @Test
    void cuaSoTraTheoCauHinhVaDoiCauHinhSauKhongDoiDonCu() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        OrderResponse o = posOrder(a, 1);
        h.updateSettings.execute(new UpdateSalesSettingsCommand(h.tenant, h.user, 500, true, 30, 0, 5, 12, 0));
        h.clock.advanceSeconds(20L * 86_400);
        assertFalse(h.tx(() -> h.createReturn.create(ret(o, 1, Disposition.RESTOCK, null))).late());
    }

    @Test
    void chiDonDaHoanTatVaDaThuMoiTraDuoc() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        UUID customer = UUID.randomUUID();
        var online = h.tx(() -> h.placeOnline.place(new lms.sales.ordering.application.PlaceOnlineOrderCommand(
                h.tenant, customer, List.of(new lms.sales.ordering.application.PlaceOnlineOrderCommand.Line(a, 1)),
                lms.sales.ordering.domain.Fulfilment.PICKUP, PaymentMethod.CASH,
                new lms.sales.ordering.domain.Recipient("An", "0900000000", null), UUID.randomUUID())));
        OrderResponse o = online.order();
        assertEquals(ErrorCode.ORDER_STATE_CONFLICT, codeOf(() -> h.createReturn.create(ret(o, 1,
                Disposition.RESTOCK, null))));
        assertEquals(ErrorCode.ORDER_NOT_FOUND, codeOf(() -> h.createReturn.create(new CreateReturnCommand(h.tenant,
                h.user, UUID.randomUUID(), List.of(new CreateReturnCommand.Line(UUID.randomUUID(), 1,
                Disposition.RESTOCK)), "x", null, UUID.randomUUID()))));
    }

    @Test
    void guiLaiCungClientRequestIdKhongTraHaiLanVaThieuLyDoBiTuChoi() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        OrderResponse o = posOrder(a, 2);
        CreateReturnCommand c = ret(o, 1, Disposition.RESTOCK, null);
        ReturnResponse first = h.tx(() -> h.createReturn.create(c));
        ReturnResponse again = h.tx(() -> h.createReturn.create(c));
        assertEquals(first.id(), again.id());
        assertEquals(9, h.item(a).onHand());
        assertEquals(ErrorCode.REASON_REQUIRED, codeOf(() -> h.createReturn.create(new CreateReturnCommand(h.tenant,
                h.user, o.id(), c.lines(), " ", null, UUID.randomUUID()))));
        assertEquals(1, h.returnQueries.listByOrder(h.tenant, o.id()).size());
        assertEquals(first.id(), h.returnQueries.get(h.tenant, first.id()).id());
        assertEquals(ErrorCode.RETURN_NOT_FOUND, codeOf(() -> h.returnQueries.get(h.tenant, UUID.randomUUID())));
    }

    @Test
    void doiHangLaPhieuTraCongDonPosMoiGanVoiPhieuTra() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        UUID b = h.book("Sách B", 80_000, null, 10);
        OrderResponse o = posOrder(a, 1);
        ReturnResponse r = h.tx(() -> h.createReturn.create(ret(o, 1, Disposition.RESTOCK, null)));
        OrderResponse swap = h.tx(() -> h.placePos.create(new PlacePosOrderCommand(h.tenant, h.user,
                List.of(new PlacePosOrderCommand.Line(b, 1, null)), PaymentMethod.CASH, null, null, null, null,
                UUID.randomUUID(), r.id())));
        assertEquals(r.id(), swap.exchangeOfReturnId());
        assertEquals(9, h.item(b).onHand());
        assertEquals(10, h.item(a).onHand());
        assertEquals(ErrorCode.RETURN_NOT_FOUND, codeOf(() -> h.placePos.create(new PlacePosOrderCommand(h.tenant,
                h.user, List.of(new PlacePosOrderCommand.Line(b, 1, null)), PaymentMethod.CASH, null, null, null,
                null, UUID.randomUUID(), UUID.randomUUID()))));
    }
}
