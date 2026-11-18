package lms.identity.apiclient.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.identity.apiclient.domain.ApplicationException;
import lms.identity.apiclient.domain.ApplicationException.Reason;
import lms.identity.apiclient.domain.ApplicationRepository;

/**
 * Thống kê sử dụng của MỘT Application theo ngày (UTC). Application phải thuộc tenant của người gọi (id của tenant khác
 * là APPLICATION_NOT_FOUND, cùng thân với "không tồn tại"; kiểm TRƯỚC khi kiểm khoảng ngày và đọc bộ đếm). Chỉ có tổng
 * yêu cầu; bộ đếm chưa tách thành công/lỗi hay theo nhóm endpoint nên không có các số đó.
 */
public class QueryApplicationUsageUseCase {

    public static final int MAX_DAYS = 31;
    public static final int DEFAULT_DAYS = 30;

    public record DailyUsage(LocalDate date, long totalRequests) {
    }

    public record UsageReport(UUID applicationId, LocalDate from, LocalDate to, long totalRequests,
                              List<DailyUsage> days) {
    }

    private final ApplicationRepository applications;
    private final ApplicationUsagePort usage;
    private final Clock clock;

    public QueryApplicationUsageUseCase(ApplicationRepository applications, ApplicationUsagePort usage, Clock clock) {
        this.applications = applications;
        this.usage = usage;
        this.clock = clock;
    }

    /**
     * {@code from}/{@code to} tùy chọn, gồm cả hai đầu: thiếu {@code to} = hôm nay (UTC), thiếu {@code from} = 30 ngày
     * kết thúc ở {@code to}.
     */
    public UsageReport usage(UUID tenantId, UUID applicationId, LocalDate from, LocalDate to) {
        applications.findByIdAndTenant(applicationId, tenantId).orElseThrow(
                () -> new ApplicationException(Reason.APPLICATION_NOT_FOUND, "Không tìm thấy ứng dụng"));
        LocalDate end = to == null ? LocalDate.now(clock.withZone(ZoneOffset.UTC)) : to;
        LocalDate start = from == null ? end.minusDays(DEFAULT_DAYS - 1L) : from;
        if (start.isAfter(end)) {
            throw new ApplicationException(Reason.INVALID_APPLICATION, "from phải không sau to");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_DAYS) {
            throw new ApplicationException(Reason.INVALID_APPLICATION,
                    "Khoảng thống kê tối đa " + MAX_DAYS + " ngày");
        }
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) dates.add(d);
        Map<LocalDate, Long> counted = usage.dailyRequests(tenantId, applicationId, dates);
        List<DailyUsage> days = dates.stream().map(d -> new DailyUsage(d, counted.getOrDefault(d, 0L))).toList();
        long total = days.stream().mapToLong(DailyUsage::totalRequests).sum();
        return new UsageReport(applicationId, start, end, total, days);
    }
}
