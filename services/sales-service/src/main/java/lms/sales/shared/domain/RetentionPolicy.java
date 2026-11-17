package lms.sales.shared.domain;

import java.time.Instant;
import java.time.ZoneOffset;

/** Chính sách lưu giữ dữ liệu khách vãng lai (ADR-0010 mục 2.8): hạn 1..120 tháng, mốc cắt tính theo UTC. */
public final class RetentionPolicy {

    public static final int MIN_MONTHS = 1;
    public static final int MAX_MONTHS = 120;

    private RetentionPolicy() {
    }

    /** Đơn kết thúc (completedAt/cancelledAt) trước mốc này thì được ẩn danh. */
    public static Instant cutoff(Instant now, int months) {
        if (months < MIN_MONTHS || months > MAX_MONTHS) {
            throw SalesException.invalidRequest("Thời hạn lưu giữ phải từ 1 đến 120 tháng");
        }
        return now.atZone(ZoneOffset.UTC).minusMonths(months).toInstant();
    }
}
