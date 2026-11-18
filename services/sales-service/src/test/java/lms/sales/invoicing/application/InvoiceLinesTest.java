package lms.sales.invoicing.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lms.sales.invoicing.domain.InvoiceLine;
import lms.sales.ordering.domain.Discount;
import lms.sales.ordering.domain.PriceCalculator;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.Rate;
import lms.sales.shared.domain.Rounding;
import org.junit.jupiter.api.Test;

/**
 * Làm tròn thuế theo dòng (ADR-0010 2.4) so với tổng đơn: hóa đơn sao NGUYÊN tiền thuế từng dòng của đơn (tổng thuế =
 * thuế của đơn, tổng tiền = tổng đơn). Tính lại thuế từ tổng sẽ lệch 1 đồng so với tổng các dòng; hóa đơn không tính lại.
 */
class InvoiceLinesTest {

    private static InvoiceOrder orderOf(PriceCalculator.Result r, List<Long> unitPrices, List<Integer> qty, int bps,
                                        boolean inclusive) {
        List<InvoiceOrder.Line> lines = new ArrayList<>();
        for (int i = 0; i < r.lines().size(); i++) {
            var p = r.lines().get(i);
            lines.add(new InvoiceOrder.Line(UUID.randomUUID(), "Sách " + i, "ISBN-" + i, qty.get(i),
                    Money.of(unitPrices.get(i)), p.lineDiscount().plus(p.allocatedOrderDiscount()), p.vatAmount(),
                    p.total()));
        }
        return new InvoiceOrder(UUID.randomUUID(), "DH-000001", "POS", "COMPLETED", "PAID", "CASH", null, inclusive,
                bps, r.shippingFee(), r.grandTotal(), false, lines);
    }

    private static PriceCalculator.Result calc(List<Long> prices, List<Integer> qty, Discount orderDiscount,
                                               boolean inclusive, long ship) {
        List<PriceCalculator.LineInput> in = new ArrayList<>();
        for (int i = 0; i < prices.size(); i++) {
            in.add(new PriceCalculator.LineInput(Money.of(prices.get(i)), qty.get(i), null));
        }
        return PriceCalculator.calculate(in, orderDiscount, new PriceCalculator.TaxPolicy(Rate.ofBps(500), inclusive),
                Money.of(ship));
    }

    @Test
    void giaGomThueTongThueCacDongKhacThueTinhTuTongNhungHoaDonGiuTongCacDong() {
        List<Long> prices = List.of(1_001L, 1_001L, 1_001L);
        List<Integer> qty = List.of(1, 1, 1);
        var r = calc(prices, qty, null, true, 0);
        long perLine = r.vatTotal().amount();
        long fromTotal = Rounding.mulDivHalfUp(r.grandTotal().amount(), 500, 10_500);
        assertThat(perLine).isEqualTo(144);
        assertThat(fromTotal).as("tính lại từ tổng lệch 1 đồng").isEqualTo(143);

        List<InvoiceLine> lines = InvoiceIssuing.linesOf(orderOf(r, prices, qty, 500, true));
        assertThat(lines.stream().mapToLong(l -> l.vatAmount().amount()).sum()).isEqualTo(perLine);
        assertThat(lines.stream().mapToLong(l -> l.lineTotal().amount()).sum()).isEqualTo(r.grandTotal().amount());
        assertThat(lines.stream().mapToLong(l -> l.amountBeforeVat().amount()).sum() + perLine)
                .isEqualTo(r.grandTotal().amount());
        assertThat(lines).allSatisfy(l -> assertThat(l.vatRateBps()).isEqualTo(500));
    }

    @Test
    void giaChuaGomThueCungSaoNguyenThueTungDong() {
        List<Long> prices = List.of(1_010L, 1_010L, 1_010L);
        List<Integer> qty = List.of(1, 1, 1);
        var r = calc(prices, qty, null, false, 0);
        assertThat(r.vatTotal().amount()).isEqualTo(153); // 3 x round(50,5) = 3 x 51
        assertThat(Rounding.mulDivHalfUp(3_030, 500, 10_000)).as("tính từ tổng").isEqualTo(152);
        List<InvoiceLine> lines = InvoiceIssuing.linesOf(orderOf(r, prices, qty, 500, false));
        assertThat(lines.stream().mapToLong(l -> l.vatAmount().amount()).sum()).isEqualTo(153);
        // chưa gồm thuế: thành tiền dòng = tiền hàng + thuế; trước thuế = tiền hàng
        assertThat(lines.get(0).amountBeforeVat().amount()).isEqualTo(1_010);
        assertThat(lines.get(0).lineTotal().amount()).isEqualTo(1_061);
    }

    @Test
    void giamGiaDonPhanBoVeDongVaPhiShipLaDongKhongChiuThue() {
        List<Long> prices = List.of(50_000L, 30_001L, 20_003L);
        List<Integer> qty = List.of(2, 1, 3);
        var r = calc(prices, qty, Discount.amount(7_777), true, 25_000);
        InvoiceOrder order = orderOf(r, prices, qty, 500, true);
        List<InvoiceLine> lines = InvoiceIssuing.linesOf(order);
        assertThat(lines).hasSize(4);
        InvoiceLine ship = lines.get(3);
        assertThat(ship.vatRateBps()).isNull();
        assertThat(ship.amountBeforeVat().amount()).isEqualTo(25_000);
        assertThat(ship.vatAmount().isZero()).isTrue();
        // tổng giảm giá trên hóa đơn = giảm giá dòng + phân bổ giảm giá đơn
        assertThat(lines.subList(0, 3).stream().mapToLong(l -> l.discount().amount()).sum()).isEqualTo(7_777);
        // tổng: bằng tổng đơn, thuế bằng thuế đơn
        assertThat(lines.stream().mapToLong(l -> l.lineTotal().amount()).sum()).isEqualTo(r.grandTotal().amount());
        assertThat(lines.stream().mapToLong(l -> l.vatAmount().amount()).sum()).isEqualTo(r.vatTotal().amount());
        // số thứ tự liên tục
        assertThat(lines).extracting(InvoiceLine::lineNo).containsExactly(1, 2, 3, 4);
    }

    @Test
    void thueSuatKhongPhanTramVanLaDongCoThueSuatKhongPhaiKhongChiuThue() {
        var r = PriceCalculator.calculate(List.of(new PriceCalculator.LineInput(Money.of(10_000), 1, null)), null,
                new PriceCalculator.TaxPolicy(Rate.ofBps(0), true), Money.ZERO);
        InvoiceOrder o = new InvoiceOrder(UUID.randomUUID(), "DH-1", "POS", "COMPLETED", "PAID", "CASH", null, true, 0,
                Money.ZERO, r.grandTotal(), false, List.of(new InvoiceOrder.Line(UUID.randomUUID(), "A", null, 1,
                Money.of(10_000), Money.ZERO, r.lines().get(0).vatAmount(), r.lines().get(0).total())));
        InvoiceLine l = InvoiceIssuing.linesOf(o).get(0);
        assertThat(l.vatRateBps()).isEqualTo(0);
        assertThat(l.vatAmount().isZero()).isTrue();
    }

    @Test
    void tongDongLechTongDonThiBaoLoiThayVaImLang() {
        var r = calc(List.of(10_000L), List.of(1), null, true, 0);
        InvoiceOrder bad = new InvoiceOrder(UUID.randomUUID(), "DH-1", "POS", "COMPLETED", "PAID", "CASH", null, true,
                500, Money.ZERO, Money.of(r.grandTotal().amount() + 1), false, List.of(new InvoiceOrder.Line(
                UUID.randomUUID(), "A", null, 1, Money.of(10_000), Money.ZERO, r.lines().get(0).vatAmount(),
                r.lines().get(0).total())));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> InvoiceIssuing.linesOf(bad));
    }
}
