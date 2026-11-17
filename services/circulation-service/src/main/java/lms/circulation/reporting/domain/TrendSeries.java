package lms.circulation.reporting.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Dựng chuỗi mốc đủ liên tục (mốc không có dữ liệu là 0) từ số liệu theo ngày; thuần Java để test biên múi giờ/tuần. */
public final class TrendSeries {

    private TrendSeries() {
    }

    /**
     * Gộp số liệu theo ngày vào bucket (ngày hoặc tuần ISO) và điền đủ mọi bucket từ bucket chứa {@code from} tới bucket
     * chứa {@code to}. Ngày ngoài khoảng bị bỏ qua. Bucket tuần đầu/cuối chỉ có phần ngày nằm trong khoảng.
     */
    public static List<TrendPoint> build(DateRange range, Granularity granularity,
                                         Map<LocalDate, Long> borrowedByDay, Map<LocalDate, Long> returnedByDay) {
        Map<LocalDate, long[]> buckets = new TreeMap<>();
        LocalDate last = granularity.bucketOf(range.to());
        for (LocalDate b = granularity.bucketOf(range.from()); !b.isAfter(last); b = granularity.next(b)) {
            buckets.put(b, new long[2]);
        }
        add(buckets, range, granularity, borrowedByDay, 0);
        add(buckets, range, granularity, returnedByDay, 1);
        List<TrendPoint> points = new ArrayList<>(buckets.size());
        buckets.forEach((bucket, v) -> points.add(new TrendPoint(bucket, v[0], v[1])));
        return points;
    }

    private static void add(Map<LocalDate, long[]> buckets, DateRange range, Granularity granularity,
                            Map<LocalDate, Long> byDay, int slot) {
        byDay.forEach((day, count) -> {
            if (day.isBefore(range.from()) || day.isAfter(range.to())) return;
            buckets.get(granularity.bucketOf(day))[slot] += count;
        });
    }
}
