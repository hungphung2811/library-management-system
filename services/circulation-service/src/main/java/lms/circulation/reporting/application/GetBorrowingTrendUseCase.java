package lms.circulation.reporting.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import lms.circulation.reporting.domain.DateRange;
import lms.circulation.reporting.domain.Granularity;
import lms.circulation.reporting.domain.TrendPoint;
import lms.circulation.reporting.domain.TrendSeries;
import org.springframework.transaction.annotation.Transactional;

/** Mượn/trả theo ngày hoặc tuần ISO (giờ VN), điền đủ mốc 0. Mặc định 30 ngày gần nhất, granularity day. */
public class GetBorrowingTrendUseCase {

    private final LendingStatsPort stats;
    private final Clock clock;

    public GetBorrowingTrendUseCase(LendingStatsPort stats, Clock clock) {
        this.stats = stats;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public BorrowingTrendResponse execute(LocalDate from, LocalDate to, String granularityText) {
        Granularity granularity = Granularity.parse(granularityText);
        DateRange range = DateRange.resolve(from, to, LocalDate.now(clock));
        List<TrendPoint> points = TrendSeries.build(range, granularity, stats.borrowedPerDay(range), stats.returnedPerDay(range));
        return new BorrowingTrendResponse(range.from(), range.to(), granularity.label(),
                points.stream().map(p -> new BorrowingTrendResponse.Point(p.bucket(), p.borrowed(), p.returned())).toList());
    }
}
