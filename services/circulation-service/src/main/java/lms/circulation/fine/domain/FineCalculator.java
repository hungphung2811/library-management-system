package lms.circulation.fine.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Domain Service thuần: tính phí phạt theo QĐ3, QĐ4, QĐ8. Tiền tệ là VND nên làm tròn về số nguyên (HALF_UP). */
public class FineCalculator {

    private final FineRates rates;

    public FineCalculator(FineRates rates) {
        this.rates = rates;
    }

    /** Tỉ lệ hỏng mặc định (cấu hình chung) dùng cho DAMAGED không kèm bậc (LEGACY_DAMAGED). */
    public BigDecimal defaultDamagedRate() {
        return rates.damagedRate();
    }

    /** Tỉ lệ mất mặc định (cấu hình chung) khi tenant chưa có chính sách đền bù. */
    public BigDecimal defaultLostRate() {
        return rates.lostRate();
    }

    /** Phí trễ (QĐ3) hoặc null khi không trễ hoặc phí bằng 0. Không chịu sàn/trần. */
    public FineLine lateFee(long overdueDays) {
        if (overdueDays <= 0) return null;
        FineLine line = new FineLine(FineReason.LATE_RETURN, money(rates.lateFeePerDay().multiply(BigDecimal.valueOf(overdueDays))));
        return line.amount().signum() > 0 ? line : null;
    }

    /**
     * Đền bù hỏng hoặc mất kèm snapshot: số tiền = giá gốc nhân tỉ lệ (làm tròn HALF_UP về số nguyên), rồi kẹp sàn/trần
     * (min, max có thể null = không giới hạn). Sàn lớn hơn trần là cấu hình sai (policy đã chặn khi lưu). Trả null nếu số
     * tiền cuối bằng 0.
     */
    public FineLine compensation(FineReason reason, PriceQuote quote, BigDecimal rate, String levelCode,
                                 String levelLabel, BigDecimal min, BigDecimal max, String note,
                                 java.util.List<String> evidenceUrls) {
        if (reason == FineReason.LATE_RETURN) throw new InvalidFineException("Phí trễ không dùng tính đền bù");
        if (quote == null) throw new InvalidFineException("Giá gốc không hợp lệ");
        if (rate == null || rate.signum() < 0) throw new InvalidFineException("Tỉ lệ đền bù phải >= 0");
        BigDecimal before = money(quote.basePrice().multiply(rate));
        BigDecimal amount = before;
        if (min != null && amount.compareTo(min) < 0) amount = money(min);
        if (max != null && amount.compareTo(max) > 0) amount = money(max);
        if (amount.signum() <= 0) return null;
        FineAssessment assessment = new FineAssessment(quote.basePrice(), quote.source(), quote.fallback(), rate,
                levelCode, levelLabel, before, note, evidenceUrls);
        return new FineLine(reason, amount, assessment);
    }

    /** Bản tính phạt dùng mức phạt trễ khác (chính sách của tenant), giữ nguyên tỉ lệ hỏng/mất. */
    public FineCalculator withLateFeePerDay(BigDecimal lateFeePerDay) {
        return new FineCalculator(new FineRates(lateFeePerDay, rates.damagedRate(), rates.lostRate()));
    }

    /**
     * Trả sách (UC07). Trễ thì có phí trễ (QĐ3); hỏng thì có phí hỏng (QĐ4); hỏng mà trễ thì chịu cả hai (QĐ8).
     * Không có khoản nào khi trả đúng hạn và nguyên vẹn.
     */
    public List<FineLine> assessReturn(long overdueDays, BigDecimal coverPrice, boolean damaged) {
        List<FineLine> lines = new ArrayList<>();
        if (overdueDays > 0) {
            lines.add(new FineLine(FineReason.LATE_RETURN, money(rates.lateFeePerDay().multiply(BigDecimal.valueOf(overdueDays)))));
        }
        if (damaged) {
            lines.add(new FineLine(FineReason.DAMAGED, money(requirePrice(coverPrice).multiply(rates.damagedRate()))));
        }
        return lines.stream().filter(l -> l.amount().signum() > 0).toList();
    }

    /** Báo mất (UC08): chỉ bồi thường theo giá bìa, không cộng phí trễ dù đã quá hạn (QĐ8). */
    public List<FineLine> assessLoss(BigDecimal coverPrice) {
        FineLine line = new FineLine(FineReason.LOST, money(requirePrice(coverPrice).multiply(rates.lostRate())));
        return line.amount().signum() > 0 ? List.of(line) : List.of();
    }

    private static BigDecimal requirePrice(BigDecimal coverPrice) {
        if (coverPrice == null || coverPrice.signum() < 0) throw new InvalidFineException("Giá bìa không hợp lệ");
        return coverPrice;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP);
    }
}
