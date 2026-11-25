package lms.sales.ordering.infrastructure.client;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import lms.sales.ordering.application.PaymentAnomaly;
import lms.sales.ordering.application.PaymentAnomalyLogPort;
import lms.sales.ordering.application.PaymentAnomalySource;
import lms.sales.ordering.application.PaymentAnomalyType;

/**
 * Đếm lỗi thăm dò liên tiếp theo cổng (ADR-0029 D6): từ {@code threshold} lỗi liên tiếp VÀ đã kéo dài {@code minSpan} kể
 * từ lỗi đầu thì báo {@code GATEWAY_POLL_FAILING_REPEATEDLY} (ERROR; adapter log giảm trùng 10 phút/lần); lần thành công
 * kế tiếp sau khi đã báo thì ghi một dòng INFO RECOVERED. Bộ đếm trong bộ nhớ theo instance (chạy nhiều instance thì mỗi
 * instance báo riêng, khởi động lại thì mất đếm: chấp nhận ở giai đoạn 1).
 */
public class GatewayHealthTracker {

    public static final int DEFAULT_THRESHOLD = 5;
    public static final Duration DEFAULT_MIN_SPAN = Duration.ofSeconds(30);

    private static final class State {
        int failures;
        long firstFailureAtMillis;
        boolean alerted;
    }

    private final Clock clock;
    private final PaymentAnomalyLogPort port;
    private final int threshold;
    private final Duration minSpan;
    private final Map<String, State> states = new HashMap<>();

    public GatewayHealthTracker(Clock clock, PaymentAnomalyLogPort port) {
        this(clock, port, DEFAULT_THRESHOLD, DEFAULT_MIN_SPAN);
    }

    public GatewayHealthTracker(Clock clock, PaymentAnomalyLogPort port, int threshold, Duration minSpan) {
        this.clock = clock;
        this.port = port;
        this.threshold = threshold;
        this.minSpan = minSpan;
    }

    public void onFailure(String provider, String errorKind) {
        int failures;
        synchronized (this) {
            State s = states.computeIfAbsent(provider, k -> new State());
            long now = clock.millis();
            if (s.failures == 0) {
                s.firstFailureAtMillis = now;
            }
            s.failures++;
            failures = s.failures;
            if (failures < threshold || now - s.firstFailureAtMillis < minSpan.toMillis()) {
                return;
            }
            s.alerted = true;
        }
        port.log(PaymentAnomaly.of(PaymentAnomalyType.GATEWAY_POLL_FAILING_REPEATEDLY, PaymentAnomalySource.POLL,
                provider).failures(failures).errorKind(errorKind).build());
    }

    public void onSuccess(String provider) {
        int failures;
        synchronized (this) {
            State s = states.remove(provider);
            if (s == null || !s.alerted) {
                return;
            }
            failures = s.failures;
        }
        port.recovered(provider, failures);
    }
}
