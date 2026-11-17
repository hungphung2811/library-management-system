package lms.circulation.reporting.application;

import java.time.Clock;
import java.time.LocalDate;
import lms.circulation.reporting.domain.LendingCounts;
import lms.circulation.reporting.domain.UnpaidFines;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tổng quan dashboard mượn trả. "Hôm nay" là {@code asOf} nếu có, ngược lại ngày hiện tại theo múi giờ của
 * {@link Clock} (Asia/Ho_Chi_Minh). Quá hạn: phiếu ACTIVE có dueDate &lt; hôm nay (đến hạn đúng hôm nay chưa quá hạn).
 */
public class GetLendingSummaryUseCase {

    private final LendingStatsPort stats;
    private final Clock clock;

    public GetLendingSummaryUseCase(LendingStatsPort stats, Clock clock) {
        this.stats = stats;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public LendingSummaryResponse execute(LocalDate asOf) {
        LocalDate today = asOf != null ? asOf : LocalDate.now(clock);
        LendingCounts c = stats.counts(today);
        UnpaidFines f = stats.unpaidFines();
        return new LendingSummaryResponse(today, clock.getZone().getId(),
                new LendingSummaryResponse.Lending(c.activeBorrowings(), c.overdueBorrowings(), c.overdueCopies(),
                        c.borrowedToday(), c.returnedToday()),
                new LendingSummaryResponse.Fines(f.count(), f.amount()));
    }
}
