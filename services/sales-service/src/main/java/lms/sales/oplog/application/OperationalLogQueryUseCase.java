package lms.sales.oplog.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lms.sales.oplog.domain.OperationalLogEvent;
import lms.sales.shared.application.PageResult;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;

/** Tra cứu log vận hành cho hai tab: Tích hợp (gọi ra + gọi vào) và Hệ thống. Kiểm đầu vào, không dựng SQL. */
public class OperationalLogQueryUseCase {

    public static final Duration MAX_RANGE = Duration.ofDays(31);
    public static final Duration DEFAULT_RANGE = Duration.ofDays(7);
    public static final int MAX_SIZE = 100;
    private static final Set<String> LEVELS = Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR");

    /** Bộ lọc thô từ API. {@code channel} chỉ có nghĩa ở tab tích hợp (OUTBOUND, INBOUND). */
    public record Filter(UUID tenantId, String channel, String provider, String outcome, String level,
                         boolean anomalyOnly, Instant from, Instant to) {
    }

    private final OperationalLogStore store;
    private final Clock clock;

    public OperationalLogQueryUseCase(OperationalLogStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public PageResult<OperationalLogEvent> integration(Filter f, int page, int size) {
        List<String> channels;
        String c = f.channel() == null ? "" : f.channel().trim().toUpperCase(Locale.ROOT);
        switch (c) {
            case "" -> channels = List.of(OperationalLogEvent.OUTBOUND, OperationalLogEvent.INBOUND);
            case "OUTBOUND" -> channels = List.of(OperationalLogEvent.OUTBOUND);
            case "INBOUND" -> channels = List.of(OperationalLogEvent.INBOUND);
            default -> throw new SalesException(ErrorCode.INVALID_REQUEST, "channel phải là OUTBOUND hoặc INBOUND");
        }
        return run(f, channels, page, size);
    }

    public PageResult<OperationalLogEvent> system(Filter f, int page, int size) {
        return run(f, List.of(OperationalLogEvent.SYSTEM), page, size);
    }

    private PageResult<OperationalLogEvent> run(Filter f, List<String> channels, int page, int size) {
        Instant to = f.to() != null ? f.to() : clock.instant();
        Instant from = f.from() != null ? f.from() : to.minus(DEFAULT_RANGE);
        if (!from.isBefore(to)) {
            throw new SalesException(ErrorCode.INVALID_RANGE, "from phải trước to");
        }
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw new SalesException(ErrorCode.INVALID_RANGE, "Khoảng thời gian tối đa 31 ngày");
        }
        String level = null;
        if (f.level() != null && !f.level().isBlank()) {
            level = f.level().trim().toUpperCase(Locale.ROOT);
            if (!LEVELS.contains(level)) {
                throw new SalesException(ErrorCode.INVALID_REQUEST, "level không hợp lệ");
            }
        }
        int p = Math.max(0, page);
        int s = size < 1 ? 20 : Math.min(size, MAX_SIZE);
        return store.search(new OperationalLogStore.Criteria(f.tenantId(), channels, blankToNull(f.provider()),
                blankToNull(f.outcome()), level, f.anomalyOnly(), from, to), p, s);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
