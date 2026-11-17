package lms.sales.ordering.domain;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.Rate;
import lms.sales.shared.domain.Rounding;
import lms.sales.shared.domain.SalesException;

/**
 * Tính tiền đơn (ADR-0010 mục 2.4). Số nguyên đồng, HALF_UP theo từng dòng, tổng đơn = tổng các dòng.
 * Thứ tự: gross = giá x số lượng; giảm giá dòng; giảm giá đơn phân bổ về dòng theo giá trị sau giảm dòng
 * (phần dư dồn theo phương pháp phần dư lớn nhất); amount; VAT (giá gồm thuế: vat = round(amount*r/(10000+r)),
 * total = amount; chưa gồm: vat = round(amount*r/10000), total = amount + vat). Phí ship cộng vào tổng, không chịu VAT.
 */
public final class PriceCalculator {

    private PriceCalculator() {
    }

    public record LineInput(Money unitPrice, int quantity, Discount lineDiscount) {
        public LineInput {
            if (quantity <= 0) {
                throw SalesException.invalidQuantity("Số lượng phải lớn hơn 0");
            }
        }
    }

    public record TaxPolicy(Rate vatRate, boolean pricesIncludeVat) {
    }

    public record LinePrice(Money gross, Money lineDiscount, Money allocatedOrderDiscount, Money amount,
                            Money vatAmount, Money total) {
    }

    public record Result(List<LinePrice> lines, Money subtotal, Money lineDiscountTotal, Money orderDiscountTotal,
                         Money discountTotal, Money vatTotal, Money shippingFee, Money grandTotal) {
    }

    public static Result calculate(List<LineInput> inputs, Discount orderDiscount, TaxPolicy tax, Money shippingFee) {
        if (inputs == null || inputs.isEmpty()) {
            throw SalesException.invalidRequest("Đơn cần ít nhất một dòng");
        }
        int n = inputs.size();
        long[] gross = new long[n];
        long[] lineDisc = new long[n];
        long[] net = new long[n];
        long sumNet = 0;
        for (int i = 0; i < n; i++) {
            LineInput in = inputs.get(i);
            Money g = in.unitPrice().times(in.quantity());
            Money d = in.lineDiscount() == null ? Money.ZERO : in.lineDiscount().resolve(g);
            gross[i] = g.amount();
            lineDisc[i] = d.amount();
            net[i] = gross[i] - lineDisc[i];
            sumNet = Math.addExact(sumNet, net[i]);
        }
        Money orderDisc = orderDiscount == null ? Money.ZERO : orderDiscount.resolve(Money.of(sumNet));
        long[] alloc = allocate(orderDisc.amount(), net, sumNet);

        List<LinePrice> lines = new ArrayList<>(n);
        long subtotal = 0, lineDiscTotal = 0, vatTotal = 0, lineTotals = 0;
        long r = tax.vatRate().bps();
        for (int i = 0; i < n; i++) {
            long amount = net[i] - alloc[i];
            long vat;
            long total;
            if (tax.pricesIncludeVat()) {
                vat = Rounding.mulDivHalfUp(amount, r, Rate.MAX_BPS + r);
                total = amount;
            } else {
                vat = Rounding.mulDivHalfUp(amount, r, Rate.MAX_BPS);
                total = amount + vat;
            }
            lines.add(new LinePrice(Money.of(gross[i]), Money.of(lineDisc[i]), Money.of(alloc[i]),
                    Money.of(amount), Money.of(vat), Money.of(total)));
            subtotal += gross[i];
            lineDiscTotal += lineDisc[i];
            vatTotal += vat;
            lineTotals += total;
        }
        Money ship = shippingFee == null ? Money.ZERO : shippingFee;
        Money grand = Money.of(lineTotals).plus(ship);
        return new Result(List.copyOf(lines), Money.of(subtotal), Money.of(lineDiscTotal), orderDisc,
                Money.of(lineDiscTotal).plus(orderDisc), Money.of(vatTotal), ship, grand);
    }

    /** Phân bổ giảm giá đơn theo tỷ lệ giá trị từng dòng, tổng phân bổ đúng bằng {@code total}, không dòng nào vượt giá trị. */
    static long[] allocate(long total, long[] net, long sumNet) {
        int n = net.length;
        long[] share = new long[n];
        if (total == 0) {
            return share;
        }
        if (sumNet <= 0 || total > sumNet) {
            throw new SalesException(ErrorCode.DISCOUNT_EXCEEDS_AMOUNT, "Giảm giá đơn vượt giá trị");
        }
        BigInteger bigTotal = BigInteger.valueOf(total);
        BigInteger den = BigInteger.valueOf(sumNet);
        BigInteger[] rem = new BigInteger[n];
        long allocated = 0;
        for (int i = 0; i < n; i++) {
            BigInteger[] qr = bigTotal.multiply(BigInteger.valueOf(net[i])).divideAndRemainder(den);
            share[i] = qr[0].longValueExact();
            rem[i] = qr[1];
            allocated += share[i];
        }
        long left = total - allocated;
        while (left > 0) {
            int best = -1;
            for (int i = 0; i < n; i++) {
                if (rem[i].signum() > 0 && (best < 0 || rem[i].compareTo(rem[best]) > 0)) {
                    best = i;
                }
            }
            share[best]++;
            rem[best] = BigInteger.ZERO;
            left--;
        }
        return share;
    }
}
