package lms.sales.ordering.infrastructure.client;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lms.sales.ordering.application.PaymentGatewayPort;

/**
 * Bộ hãm lời gọi dò SePay (ADR-0029 mục 2.4), trong tiến trình, không dùng Redis (RateLimiter của cache-common fail-open
 * và tắt khi LMS_CACHE_ENABLED=false). SePay giới hạn 3 yêu cầu/giây theo IP cho cả máy chủ, nên nhiều khách cùng
 * thăm dò sẽ làm hỏng cả POS nếu không hãm. Hai lớp:
 * <ul>
 *   <li>Theo lệnh: mỗi mã tham chiếu hỏi cổng tối đa một lần mỗi {@code minQueryIntervalMs}; trong khoảng đó trả
 *       {@link Optional#empty()} (lệnh giữ PENDING, FE vẫn nhận phản hồi ngay từ DB).</li>
 *   <li>Toàn cục: token bucket {@code maxQueriesPerSecond}; hết token thì trả rỗng.</li>
 * </ul>
 * {@link #queryPaymentFinal} (kiểm lần cuối trước khi hủy/hết hạn) bỏ qua lớp theo lệnh nhưng vẫn qua lớp toàn cục.
 * Giới hạn theo instance: chạy nhiều instance sales thì tổng lời gọi nhân lên. Lỗi của cổng được ném tiếp (người gọi bắt).
 */
public class ThrottledPaymentGateway implements PaymentGatewayPort {

    private final PaymentGatewayPort delegate;
    private final Clock clock;
    private final long minQueryIntervalMs;
    private final double maxQueriesPerSecond;

    /** Lần hỏi cổng gần nhất theo mã tham chiếu (mili giây). Mục cũ hơn khoảng hãm bị dọn nên map luôn nhỏ. */
    private final Map<String, Long> lastQueryAt = new HashMap<>();
    private double tokens;
    private long lastRefillAt;

    public ThrottledPaymentGateway(PaymentGatewayPort delegate, Clock clock, long minQueryIntervalMs,
                                   int maxQueriesPerSecond) {
        this.delegate = delegate;
        this.clock = clock;
        this.minQueryIntervalMs = Math.max(0, minQueryIntervalMs);
        this.maxQueriesPerSecond = Math.max(1, maxQueriesPerSecond);
        this.tokens = this.maxQueriesPerSecond;
        this.lastRefillAt = clock.millis();
    }

    @Override
    public String provider() {
        return delegate.provider();
    }

    @Override
    public QrCharge createQr(ChargeRequest request) {
        return delegate.createQr(request);
    }

    @Override
    public Optional<GatewayPayment> queryPayment(String reference) {
        return delegate.queryPayment(reference);
    }

    @Override
    public Optional<GatewayPayment> queryPayment(String reference, long amount, Instant notBefore) {
        if (!admit(reference, true)) {
            return Optional.empty();
        }
        return delegate.queryPayment(reference, amount, notBefore);
    }

    @Override
    public Optional<GatewayPayment> queryPaymentFinal(String reference, long amount, Instant notBefore) {
        if (!admit(reference, false)) {
            return Optional.empty();
        }
        return delegate.queryPayment(reference, amount, notBefore);
    }

    /**
     * Quyết định có cho gọi cổng không và ghi nhận nếu có. Chỉ khi được phép gọi mới tiêu token và đánh dấu thời điểm
     * của lệnh (bị từ chối bởi lớp theo lệnh thì không tốn token toàn cục).
     */
    private synchronized boolean admit(String reference, boolean perReference) {
        long now = clock.millis();
        if (perReference) {
            Long last = lastQueryAt.get(reference);
            if (last != null && now - last < minQueryIntervalMs) {
                return false;
            }
        }
        refill(now);
        if (tokens < 1.0) {
            return false;
        }
        tokens -= 1.0;
        lastQueryAt.values().removeIf(t -> now - t >= minQueryIntervalMs);
        lastQueryAt.put(reference, now);
        return true;
    }

    private void refill(long now) {
        long elapsed = now - lastRefillAt;
        if (elapsed > 0) {
            tokens = Math.min(maxQueriesPerSecond, tokens + elapsed / 1000.0 * maxQueriesPerSecond);
        }
        lastRefillAt = now; // đồng hồ lùi (elapsed âm) thì chỉ đặt lại mốc, không cộng token
    }
}
