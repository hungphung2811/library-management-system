package lms.support.notification.domain;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Lịch thử lại: lần thử đầu rồi tối đa {@code delays.size()} lần thử lại (mặc định 5: 1 phút, 5 phút, 30 phút,
 * 2 giờ, 6 giờ). Hết lượt thì DEAD.
 */
public record RetryPolicy(List<Duration> delays) {

    public static final RetryPolicy DEFAULT = new RetryPolicy(List.of(Duration.ofMinutes(1), Duration.ofMinutes(5),
            Duration.ofMinutes(30), Duration.ofHours(2), Duration.ofHours(6)));

    public RetryPolicy {
        if (delays == null || delays.isEmpty()) throw new IllegalArgumentException("Cần ít nhất một mốc thử lại");
        delays = List.copyOf(delays);
    }

    /** Thời gian chờ trước lần thử kế tiếp sau {@code failedAttemptsInCycle} lần thất bại; rỗng = hết lượt. */
    public Optional<Duration> delayAfter(int failedAttemptsInCycle) {
        if (failedAttemptsInCycle < 1 || failedAttemptsInCycle > delays.size()) return Optional.empty();
        return Optional.of(delays.get(failedAttemptsInCycle - 1));
    }
}
