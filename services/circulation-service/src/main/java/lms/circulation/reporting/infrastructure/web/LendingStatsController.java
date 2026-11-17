package lms.circulation.reporting.infrastructure.web;

import java.time.LocalDate;
import lms.circulation.reporting.application.BorrowingTrendResponse;
import lms.circulation.reporting.application.GetBorrowingTrendUseCase;
import lms.circulation.reporting.application.GetLendingSummaryUseCase;
import lms.circulation.reporting.application.GetTopBorrowedTitlesUseCase;
import lms.circulation.reporting.application.LendingSummaryResponse;
import lms.circulation.reporting.application.TopBorrowedTitlesResponse;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantModule;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thống kê mượn trả cho dashboard (ADR-0018). Khóa quyền động {@code LendingStatsController/<method>}; không đổi tên
 * controller/method vì đổi tên là đổi khóa. Ngày là {@code yyyy-MM-dd} theo giờ VN.
 */
@RestController
@RequiresModule(TenantModule.LENDING)
@RequestMapping("/internal/reporting/lending")
public class LendingStatsController {

    private final GetLendingSummaryUseCase summary;
    private final GetBorrowingTrendUseCase borrowingTrend;
    private final GetTopBorrowedTitlesUseCase topBorrowedTitles;

    public LendingStatsController(GetLendingSummaryUseCase summary, GetBorrowingTrendUseCase borrowingTrend,
                                  GetTopBorrowedTitlesUseCase topBorrowedTitles) {
        this.summary = summary;
        this.borrowingTrend = borrowingTrend;
        this.topBorrowedTitles = topBorrowedTitles;
    }

    /** Tổng quan mượn trả và phạt chưa thu; {@code asOf} tùy chọn (mặc định hôm nay giờ VN). */
    @GetMapping("/summary")
    public LendingSummaryResponse summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return summary.execute(asOf);
    }

    /** Mượn/trả theo ngày hoặc tuần ISO. */
    @GetMapping("/borrowing-trend")
    public BorrowingTrendResponse borrowingTrend(TrendQuery query) {
        return borrowingTrend.execute(query.from(), query.to(), query.granularity());
    }

    /** Top đầu sách được mượn (dòng cũ không có title_id bị bỏ qua). */
    @GetMapping("/top-titles")
    public TopBorrowedTitlesResponse topBorrowedTitles(TopTitlesQuery query) {
        return topBorrowedTitles.execute(query.from(), query.to(), query.limit());
    }

    /** Tham số của biểu đồ mượn/trả; thiếu from/to thì 30 ngày gần nhất, thiếu granularity thì {@code day}. */
    public record TrendQuery(@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                             String granularity) {
    }

    /** Tham số của top sách mượn; thiếu limit thì 10 (hợp lệ 1..50). */
    public record TopTitlesQuery(@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                 Integer limit) {
    }
}
