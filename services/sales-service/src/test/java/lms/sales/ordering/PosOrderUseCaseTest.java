package lms.sales.ordering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import lms.sales.ordering.application.OrderQueryUseCases;
import lms.sales.ordering.application.OrderResponse;
import lms.sales.ordering.application.PlacePosOrderCommand;
import lms.sales.ordering.domain.Discount;
import lms.sales.ordering.domain.OrderChannel;
import lms.sales.ordering.domain.OrderStatus;
import lms.sales.ordering.domain.PaymentMethod;
import lms.sales.ordering.domain.PaymentStatus;
import lms.sales.shared.domain.PersonalDataMasker;
import lms.sales.settings.application.UpdateSalesSettingsCommand;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.application.StockLine;
import lms.sales.stock.domain.MovementType;
import lms.sales.support.Harness;
import org.junit.jupiter.api.Test;

class PosOrderUseCaseTest {

    private final Harness h = new Harness();

    private PlacePosOrderCommand cmd(UUID cid, PaymentMethod pm, Discount orderDiscount,
                                     PlacePosOrderCommand.Line... lines) {
        return new PlacePosOrderCommand(h.tenant, h.user, List.of(lines), pm, null, "Nguyễn Văn An", "0912345678",
                orderDiscount, cid);
    }

    private static PlacePosOrderCommand.Line line(UUID title, int qty) {
        return new PlacePosOrderCommand.Line(title, qty, null);
    }

    private static PlacePosOrderCommand.Line line(UUID title, int qty, Discount d) {
        return new PlacePosOrderCommand.Line(title, qty, d);
    }

    private ErrorCode codeOf(Runnable r) {
        return assertThrows(SalesException.class, r::run).code();
    }

    @Test
    void posOrderCompletesPaidDeductsStockAndSnapshotsData() {
        UUID a = h.book("Dế Mèn Phiêu Lưu Ký", 100_000, null, 10);
        OrderResponse o = h.tx(() -> h.placePos.create(cmd(UUID.randomUUID(), PaymentMethod.CASH, null,
                line(a, 2))));

        assertEquals(OrderChannel.POS, o.channel());
        assertEquals(OrderStatus.COMPLETED, o.status());
        assertEquals(PaymentStatus.PAID, o.paymentStatus());
        assertEquals(200_000, o.grandTotal());
        assertEquals(9_524, o.vatTotal());        // 200000 * 500 / 10500 = 9523.8, giá đã gồm thuế
        assertEquals(500, o.vatRateBps());
        assertTrue(o.pricesIncludeVat());
        assertEquals("Dế Mèn Phiêu Lưu Ký", o.lines().get(0).titleName());
        assertEquals("ISBN-Dế Mèn Phiêu Lưu Ký", o.lines().get(0).isbn());
        assertEquals(100_000, o.lines().get(0).unitPrice());

        assertEquals(8, h.item(a).onHand());
        var last = h.ledger().get(h.ledger().size() - 1);
        assertEquals(MovementType.FULFIL, last.type()); // ADR-0021: hai bước, tồn giữ rồi xuất khi xác nhận thu
        assertEquals(-2, last.deltaOnHand());
        assertTrue(h.reconcile.execute(h.tenant, a).consistent());
        assertTrue(h.audits().stream().anyMatch(e -> e.action().equals(SalesAuditPort.Actions.ORDER_CREATED)
                && e.data().get("grandTotal").equals(200_000L)));
    }

    @Test
    void sellingPriceOverridesCoverPrice() {
        UUID a = h.book("A", 100_000, 80_000L, 5);
        OrderResponse o = h.placePos.create(cmd(UUID.randomUUID(), PaymentMethod.CARD, null, line(a, 1)));
        assertEquals(80_000, o.lines().get(0).unitPrice());
    }

    @Test
    void oversellIsRejectedWithoutPartialOrder() {
        UUID a = h.book("A", 10_000, null, 5);
        UUID b = h.book("B", 10_000, null, 1);
        int ledgerBefore = h.ledger().size();
        var e = assertThrows(SalesException.class, () -> h.tx(() -> h.placePos.create(cmd(UUID.randomUUID(),
                PaymentMethod.CASH, null, line(a, 2), line(b, 3)))));
        assertEquals(ErrorCode.INSUFFICIENT_STOCK, e.code());
        assertEquals(b.toString(), e.details().get("titleId"));
        assertEquals(1L, e.details().get("available"));
        assertEquals(0, h.orderCount());
        assertEquals(5, h.item(a).onHand());   // dòng A không bị trừ
        assertEquals(1, h.item(b).onHand());
        assertEquals(ledgerBefore, h.ledger().size());
    }

    @Test
    void reservedStockCannotBeSoldByPos() {
        UUID a = h.book("A", 10_000, null, 5);
        h.stockService.reserveAll(h.tenant, h.user, List.of(new StockLine(a, 4)), "ORDER", UUID.randomUUID());
        assertEquals(ErrorCode.INSUFFICIENT_STOCK, codeOf(() -> h.placePos.create(cmd(UUID.randomUUID(),
                PaymentMethod.CASH, null, line(a, 2)))));
        h.placePos.create(cmd(UUID.randomUUID(), PaymentMethod.CASH, null, line(a, 1)));
        assertEquals(4, h.item(a).onHand());
    }

    @Test
    void duplicateLinesOfSameTitleAreCheckedTogether() {
        UUID a = h.book("A", 10_000, null, 5);
        assertEquals(ErrorCode.INSUFFICIENT_STOCK, codeOf(() -> h.placePos.create(cmd(UUID.randomUUID(),
                PaymentMethod.CASH, null, line(a, 3), line(a, 3)))));
        OrderResponse o = h.placePos.create(cmd(UUID.randomUUID(), PaymentMethod.CASH, null, line(a, 3),
                line(a, 2)));
        assertEquals(2, o.lines().size());  // dòng đơn giữ nguyên
        assertEquals(0, h.item(a).onHand());
    }

    @Test
    void sameClientRequestIdReturnsSameOrderAndDeductsOnce() {
        UUID a = h.book("A", 10_000, null, 5);
        UUID cid = UUID.randomUUID();
        OrderResponse first = h.tx(() -> h.placePos.create(cmd(cid, PaymentMethod.CASH, null, line(a, 2))));
        int catalogCalls = h.catalogCalls;
        OrderResponse second = h.tx(() -> h.placePos.create(cmd(cid, PaymentMethod.CASH, null, line(a, 2))));
        assertEquals(first.id(), second.id());
        assertEquals(first.orderNo(), second.orderNo());
        assertEquals(3, h.item(a).onHand());
        assertEquals(1, h.orderCount());
        assertEquals(catalogCalls, h.catalogCalls); // trả sớm, không gọi catalog lại
    }

    @Test
    void concurrentDuplicateRequestFallsBackToWinnerOrder() {
        UUID a = h.book("A", 10_000, null, 5);
        UUID cid = UUID.randomUUID();
        Harness other = new Harness();
        UUID oa = other.book("A", 10_000, null, 5);
        var winner = other.orderRepo.findById(other.tenant,
                other.placePos.create(cmd(cid, PaymentMethod.CASH, null, line(oa, 1))).id()).orElseThrow();
        h.raceWinner = winner;
        OrderResponse r = h.placePos.create(cmd(cid, PaymentMethod.CASH, null, line(a, 1)));
        assertEquals(winner.id(), r.id());
        assertEquals(1, h.orderCount());
    }

    @Test
    void createRejectsDiscountButCreateWithDiscountAppliesIt() {
        UUID a = h.book("A", 100_000, null, 10);
        assertEquals(ErrorCode.DISCOUNT_NOT_ALLOWED, codeOf(() -> h.placePos.create(cmd(UUID.randomUUID(),
                PaymentMethod.CASH, null, line(a, 1, Discount.percent(1000))))));
        assertEquals(ErrorCode.DISCOUNT_NOT_ALLOWED, codeOf(() -> h.placePos.create(cmd(UUID.randomUUID(),
                PaymentMethod.CASH, Discount.amount(1_000), line(a, 1)))));
        assertEquals(0, h.orderCount());

        // 2 x 100000, giảm dòng 10% = 20000 -> 180000, giảm đơn 9000 -> 171000
        OrderResponse o = h.placePos.createWithDiscount(cmd(UUID.randomUUID(), PaymentMethod.BANK_TRANSFER,
                Discount.amount(9_000), line(a, 2, Discount.percent(1000))));
        assertEquals(200_000, o.subtotal());
        assertEquals(29_000, o.discountTotal());
        assertEquals(171_000, o.grandTotal());
        assertEquals(8_143, o.vatTotal());  // 171000*500/10500 = 8142.86
        assertEquals(20_000, o.lines().get(0).lineDiscount());
        assertEquals(9_000, o.lines().get(0).allocatedOrderDiscount());
        assertTrue(h.audits().stream().anyMatch(e -> e.action().equals(SalesAuditPort.Actions.ORDER_DISCOUNT_APPLIED)));

        var e = assertThrows(SalesException.class, () -> h.placePos.createWithDiscount(cmd(UUID.randomUUID(),
                PaymentMethod.CASH, Discount.amount(500_000), line(a, 1))));
        assertEquals(ErrorCode.DISCOUNT_EXCEEDS_AMOUNT, e.code());
        assertEquals(8, h.item(a).onHand()); // đơn lỗi không trừ kho
    }

    @Test
    void settingsChangeAffectsOnlyNewOrders() {
        UUID a = h.book("A", 100_000, null, 10);
        OrderResponse old = h.placePos.create(cmd(UUID.randomUUID(), PaymentMethod.CASH, null, line(a, 1)));
        h.updateSettings.execute(new UpdateSalesSettingsCommand(h.tenant, h.user, 1000, false, 7, 0, 5, 12, 0));
        OrderResponse fresh = h.placePos.create(cmd(UUID.randomUUID(), PaymentMethod.CASH, null, line(a, 1)));

        assertEquals(500, h.orderQueries.get(h.tenant, old.id()).vatRateBps());
        assertEquals(100_000, h.orderQueries.get(h.tenant, old.id()).grandTotal());
        assertEquals(1000, fresh.vatRateBps());
        assertFalse(fresh.pricesIncludeVat());
        assertEquals(10_000, fresh.vatTotal());
        assertEquals(110_000, fresh.grandTotal()); // chưa gồm thuế: cộng 10%
    }

    @Test
    void invalidRequestsAreRejected() {
        UUID a = h.book("A", 1_000, null, 5);
        UUID unregistered = UUID.randomUUID();
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> h.placePos.create(cmd(UUID.randomUUID(),
                PaymentMethod.COD, null, line(a, 1)))));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> h.placePos.create(cmd(null, PaymentMethod.CASH, null,
                line(a, 1)))));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> h.placePos.create(cmd(UUID.randomUUID(),
                PaymentMethod.CASH, null))));
        assertEquals(ErrorCode.INVALID_QUANTITY, codeOf(() -> h.placePos.create(cmd(UUID.randomUUID(),
                PaymentMethod.CASH, null, line(a, 0)))));
        assertEquals(ErrorCode.INVALID_QUANTITY, codeOf(() -> h.placePos.create(cmd(UUID.randomUUID(),
                PaymentMethod.CASH, null, line(a, 10_001)))));
        assertEquals(ErrorCode.STOCK_ITEM_NOT_REGISTERED, codeOf(() -> h.placePos.create(cmd(UUID.randomUUID(),
                PaymentMethod.CASH, null, line(unregistered, 1)))));
        assertEquals(0, h.orderCount());
    }

    @Test
    void titleMissingInCatalogOrInactiveIsNotAvailable() {
        UUID a = h.book("A", 1_000, null, 5);
        h.catalogTitles.remove(a);
        assertEquals(ErrorCode.TITLE_NOT_AVAILABLE, codeOf(() -> h.placePos.create(cmd(UUID.randomUUID(),
                PaymentMethod.CASH, null, line(a, 1)))));
        assertEquals(5, h.item(a).onHand());
    }

    @Test
    void personalDataIsMaskedInResponsesAndNotInToStringOrAudit() {
        UUID a = h.book("A", 1_000, null, 5);
        OrderResponse o = h.placePos.create(cmd(UUID.randomUUID(), PaymentMethod.CASH, null, line(a, 1)));
        assertEquals("N***** V** A*", o.walkInNameMasked());
        assertEquals("*******678", o.walkInPhoneMasked());

        OrderQueryUseCases.Contact c = h.orderQueries.contact(h.tenant, o.id());
        assertEquals("Nguyễn Văn An", c.name());
        assertEquals("0912345678", c.phone());
        assertFalse(c.toString().contains("Nguy"));

        var order = h.orderRepo.findById(h.tenant, o.id()).orElseThrow();
        assertFalse(order.toString().contains("0912"));
        assertFalse(cmd(UUID.randomUUID(), PaymentMethod.CASH, null, line(a, 1)).toString().contains("0912"));
        h.audits().forEach(e -> assertFalse(e.data().toString().contains("Nguy")
                || e.data().toString().contains("0912")));
        assertNull(PersonalDataMasker.maskName(" "));
        assertEquals("**", PersonalDataMasker.maskPhone("12"));
    }

    @Test
    void receiptContainsTaxBreakdownWithShippingOutsideTaxableBase() {
        UUID a = h.book("A", 100_000, null, 5);
        OrderResponse o = h.placePos.create(cmd(UUID.randomUUID(), PaymentMethod.CASH, null, line(a, 3)));
        var r = h.orderQueries.receipt(h.tenant, o.id());
        assertEquals(o.orderNo(), r.orderNo());
        assertEquals(300_000, r.subtotal());
        assertEquals(300_000, r.taxableAmount());
        assertEquals(0, r.shippingFee());
        assertEquals(300_000, r.grandTotal());
        assertEquals(500, r.vatRateBps());
        assertEquals(1, r.lines().size());
        assertEquals(ErrorCode.ORDER_NOT_FOUND, codeOf(() -> h.orderQueries.get(h.tenant, UUID.randomUUID())));
    }

    @Test
    void searchFiltersByStatusAndChannel() {
        UUID a = h.book("A", 1_000, null, 9);
        h.placePos.create(cmd(UUID.randomUUID(), PaymentMethod.CASH, null, line(a, 1)));
        h.placePos.create(cmd(UUID.randomUUID(), PaymentMethod.CARD, null, line(a, 1)));
        var page = h.orderQueries.search(h.tenant, new OrderQueryUseCases.SearchQuery(OrderStatus.COMPLETED, null,
                OrderChannel.POS, null, null, null, 0, 20));
        assertEquals(2, page.items().size());
        var none = h.orderQueries.search(h.tenant, new OrderQueryUseCases.SearchQuery(OrderStatus.CANCELLED, null,
                null, null, null, null, 0, 20));
        assertEquals(0, none.total());
    }
}
