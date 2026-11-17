package lms.circulation.reporting.application;

import java.time.Clock;
import java.time.LocalDate;
import lms.circulation.reporting.domain.DateRange;
import lms.circulation.reporting.domain.InvalidReportQueryException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Top đầu sách theo số bản sao mượn (borrowDate trong khoảng). Dòng mượn cũ chưa có title_id không được tính.
 * limit mặc định {@value #DEFAULT_LIMIT}, hợp lệ 1..{@value #MAX_LIMIT}.
 */
public class GetTopBorrowedTitlesUseCase {

    public static final int DEFAULT_LIMIT = 10;
    public static final int MAX_LIMIT = 50;

    private final LendingStatsPort stats;
    private final Clock clock;

    public GetTopBorrowedTitlesUseCase(LendingStatsPort stats, Clock clock) {
        this.stats = stats;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public TopBorrowedTitlesResponse execute(LocalDate from, LocalDate to, Integer limitParam) {
        int limit = limitParam == null ? DEFAULT_LIMIT : limitParam;
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidReportQueryException("INVALID_LIMIT", "limit phải trong khoảng 1.." + MAX_LIMIT);
        }
        DateRange range = DateRange.resolve(from, to, LocalDate.now(clock));
        return new TopBorrowedTitlesResponse(range.from(), range.to(), limit,
                stats.topBorrowedTitles(range, limit).stream()
                        .map(t -> new TopBorrowedTitlesResponse.Item(t.titleId(), t.count())).toList());
    }
}
