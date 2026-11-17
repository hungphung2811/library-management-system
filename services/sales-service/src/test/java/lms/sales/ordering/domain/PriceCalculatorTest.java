package lms.sales.ordering.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import lms.sales.ordering.domain.PriceCalculator.LineInput;
import lms.sales.ordering.domain.PriceCalculator.TaxPolicy;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.Rate;
import lms.sales.shared.domain.SalesException;
import org.junit.jupiter.api.Test;

class PriceCalculatorTest {

    private static final TaxPolicy INCLUDED_5 = new TaxPolicy(Rate.ofBps(500), true);
    private static final TaxPolicy EXCLUDED_5 = new TaxPolicy(Rate.ofBps(500), false);

    private static LineInput line(long unit, int qty) {
        return new LineInput(Money.of(unit), qty, null);
    }

    private static PriceCalculator.Result calc(TaxPolicy tax, Discount orderDiscount, long ship, LineInput... in) {
        return PriceCalculator.calculate(List.of(in), orderDiscount, tax, Money.of(ship));
    }

    @Test
    void priceIncludingVat_vatIsExtractedAndTotalEqualsAmount() {
        var r = calc(INCLUDED_5, null, 0, line(105_000, 1));
        assertEquals(5_000, r.lines().get(0).vatAmount().amount());
        assertEquals(105_000, r.lines().get(0).total().amount());
        assertEquals(105_000, r.grandTotal().amount());
        assertEquals(5_000, r.vatTotal().amount());
    }

    @Test
    void priceExcludingVat_vatIsAddedOnTop() {
        var r = calc(EXCLUDED_5, null, 0, line(100_000, 1));
        assertEquals(5_000, r.lines().get(0).vatAmount().amount());
        assertEquals(105_000, r.lines().get(0).total().amount());
        assertEquals(105_000, r.grandTotal().amount());
    }

    @Test
    void halfUpAtExactHalf_excludedVat() {
        // amount * 500 / 10000 = amount / 20
        assertEquals(1, calc(EXCLUDED_5, null, 0, line(10, 1)).vatTotal().amount());   // 0.5 -> 1
        assertEquals(2, calc(EXCLUDED_5, null, 0, line(30, 1)).vatTotal().amount());   // 1.5 -> 2
        assertEquals(0, calc(EXCLUDED_5, null, 0, line(9, 1)).vatTotal().amount());    // 0.45 -> 0
        assertEquals(1, calc(EXCLUDED_5, null, 0, line(11, 1)).vatTotal().amount());   // 0.55 -> 1
    }

    @Test
    void halfUpAtExactHalf_includedVat() {
        var twenty = new TaxPolicy(Rate.ofBps(2000), true); // vat = amount / 6
        assertEquals(1, calc(twenty, null, 0, line(3, 1)).vatTotal().amount());        // 0.5 -> 1
        assertEquals(2, calc(twenty, null, 0, line(9, 1)).vatTotal().amount());        // 1.5 -> 2
        assertEquals(0, calc(twenty, null, 0, line(2, 1)).vatTotal().amount());        // 0.33 -> 0
    }

    @Test
    void lineDiscountByAmountAndPercent() {
        var amountOff = PriceCalculator.calculate(
                List.of(new LineInput(Money.of(50_000), 2, Discount.amount(15_000))), null, INCLUDED_5, Money.ZERO);
        assertEquals(100_000, amountOff.lines().get(0).gross().amount());
        assertEquals(85_000, amountOff.lines().get(0).amount().amount());

        var percentOff = PriceCalculator.calculate(
                List.of(new LineInput(Money.of(50_000), 2, Discount.percent(1000))), null, INCLUDED_5, Money.ZERO);
        assertEquals(10_000, percentOff.lines().get(0).lineDiscount().amount());
        assertEquals(90_000, percentOff.lines().get(0).amount().amount());
    }

    @Test
    void percentDiscountRoundsHalfUp() {
        // 10 dong giam 5% = 0.5 -> 1
        var r = PriceCalculator.calculate(List.of(new LineInput(Money.of(10), 1, Discount.percent(500))), null,
                new TaxPolicy(Rate.ofBps(0), true), Money.ZERO);
        assertEquals(1, r.lines().get(0).lineDiscount().amount());
        assertEquals(9, r.grandTotal().amount());
    }

    @Test
    void workedExample_includedVat_orderDiscountAllocatedAndShippingNotTaxed() {
        // A: 120000 x2, giam 20000 -> 220000; B: 85000 x1 -> 85000; giam don 10% (30500) -> phan bo 22000 / 8500
        var in = List.of(new LineInput(Money.of(120_000), 2, Discount.amount(20_000)), line(85_000, 1));
        var r = PriceCalculator.calculate(in, Discount.percent(1000), INCLUDED_5, Money.of(20_000));
        assertEquals(22_000, r.lines().get(0).allocatedOrderDiscount().amount());
        assertEquals(8_500, r.lines().get(1).allocatedOrderDiscount().amount());
        assertEquals(198_000, r.lines().get(0).amount().amount());
        assertEquals(76_500, r.lines().get(1).amount().amount());
        assertEquals(9_429, r.lines().get(0).vatAmount().amount());   // 9428.57
        assertEquals(3_643, r.lines().get(1).vatAmount().amount());   // 3642.86
        assertEquals(274_500, r.lines().get(0).total().amount() + r.lines().get(1).total().amount());
        assertEquals(13_072, r.vatTotal().amount());
        assertEquals(325_000, r.subtotal().amount());
        assertEquals(50_500, r.discountTotal().amount());
        assertEquals(30_500, r.orderDiscountTotal().amount());
        assertEquals(20_000, r.shippingFee().amount());
        assertEquals(294_500, r.grandTotal().amount());
    }

    @Test
    void workedExample_excludedVat() {
        var in = List.of(new LineInput(Money.of(120_000), 2, Discount.amount(20_000)), line(85_000, 1));
        var r = PriceCalculator.calculate(in, Discount.percent(1000), EXCLUDED_5, Money.of(20_000));
        assertEquals(9_900, r.lines().get(0).vatAmount().amount());
        assertEquals(3_825, r.lines().get(1).vatAmount().amount());
        assertEquals(207_900, r.lines().get(0).total().amount());
        assertEquals(80_325, r.lines().get(1).total().amount());
        assertEquals(13_725, r.vatTotal().amount());
        assertEquals(308_225, r.grandTotal().amount()); // 288225 + ship 20000, ship khong chiu VAT
    }

    @Test
    void orderDiscountRemainderGoesToLargestRemainder() {
        // 100 giam tren 100/200/300: floor 16/33/50 = 99, du 1 vao dong co phan du lon nhat (dong 1)
        var r = calc(new TaxPolicy(Rate.ofBps(0), true), Discount.amount(100), 0, line(100, 1), line(200, 1),
                line(300, 1));
        assertEquals(17, r.lines().get(0).allocatedOrderDiscount().amount());
        assertEquals(33, r.lines().get(1).allocatedOrderDiscount().amount());
        assertEquals(50, r.lines().get(2).allocatedOrderDiscount().amount());
        assertEquals(500, r.grandTotal().amount());
    }

    @Test
    void orderDiscountOfWholeValue_leavesZeroAmounts() {
        var r = calc(INCLUDED_5, Discount.percent(10_000), 15_000, line(100_000, 1), line(50_000, 2));
        r.lines().forEach(l -> {
            assertEquals(0, l.amount().amount());
            assertEquals(0, l.vatAmount().amount());
        });
        assertEquals(15_000, r.grandTotal().amount()); // chi con phi ship
    }

    @Test
    void discountsCannotExceedValue() {
        var e1 = assertThrows(SalesException.class, () -> PriceCalculator.calculate(
                List.of(new LineInput(Money.of(1_000), 1, Discount.amount(1_001))), null, INCLUDED_5, Money.ZERO));
        assertEquals(ErrorCode.DISCOUNT_EXCEEDS_AMOUNT, e1.code());
        var e2 = assertThrows(SalesException.class, () -> calc(INCLUDED_5, Discount.amount(2_001), 0,
                line(1_000, 2)));
        assertEquals(ErrorCode.DISCOUNT_EXCEEDS_AMOUNT, e2.code());
        var e3 = assertThrows(SalesException.class, () -> Discount.percent(10_001));
        assertEquals(ErrorCode.DISCOUNT_EXCEEDS_AMOUNT, e3.code());
    }

    @Test
    void zeroVatRate() {
        var r = calc(new TaxPolicy(Rate.ofBps(0), false), null, 0, line(12_345, 3));
        assertEquals(0, r.vatTotal().amount());
        assertEquals(37_035, r.grandTotal().amount());
    }

    @Test
    void rejectsEmptyAndNonPositiveQuantity() {
        assertThrows(SalesException.class, () -> PriceCalculator.calculate(List.of(), null, INCLUDED_5, Money.ZERO));
        assertThrows(SalesException.class, () -> new LineInput(Money.of(1), 0, null));
    }

    @Test
    void invariantsHoldForRandomOrders() {
        Random rnd = new Random(20261117L);
        for (int iter = 0; iter < 2_000; iter++) {
            int n = 1 + rnd.nextInt(6);
            List<LineInput> in = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Discount d = switch (rnd.nextInt(3)) {
                    case 0 -> null;
                    case 1 -> Discount.percent(rnd.nextInt(10_001));
                    default -> Discount.amount(0);
                };
                in.add(new LineInput(Money.of(1 + rnd.nextInt(500_000)), 1 + rnd.nextInt(9), d));
            }
            Discount orderDisc = rnd.nextBoolean() ? null : Discount.percent(rnd.nextInt(10_001));
            TaxPolicy tax = new TaxPolicy(Rate.ofBps(rnd.nextInt(2_001)), rnd.nextBoolean());
            long ship = rnd.nextInt(50_000);
            var r = PriceCalculator.calculate(in, orderDisc, tax, Money.of(ship));

            long allocated = 0;
            long totals = 0;
            long vat = 0;
            for (var l : r.lines()) {
                allocated += l.allocatedOrderDiscount().amount();
                totals += l.total().amount();
                vat += l.vatAmount().amount();
                assertEquals(l.gross().amount() - l.lineDiscount().amount() - l.allocatedOrderDiscount().amount(),
                        l.amount().amount()); // khong dong nao am (Money chan am)
            }
            assertEquals(r.orderDiscountTotal().amount(), allocated);
            assertEquals(totals + ship, r.grandTotal().amount());
            assertEquals(vat, r.vatTotal().amount());
        }
    }
}
