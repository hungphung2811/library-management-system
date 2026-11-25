package lms.sales.ordering.infrastructure.config;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lms.observability.log.IntegrationLog;
import lms.observability.log.LogChannel;
import lms.observability.log.LogThrottle;
import lms.sales.ordering.application.PaymentAnomaly;
import lms.sales.ordering.application.PaymentAnomalyLogPort;
import lms.sales.ordering.application.PaymentAnomalySource;
import lms.sales.ordering.application.PaymentAnomalyType;

/**
 * Cài đặt {@link PaymentAnomalyLogPort} bằng {@link IntegrationLog} (ADR-0029): chọn kênh và logger theo loại bất thường
 * và nguồn kích hoạt, che dữ liệu (mã tham chiếu, IP) qua {@code LogMask} do {@link IntegrationLog} áp dụng, giảm trùng
 * theo {@code (anomalyType, intentId hoặc provider)} trong 60 giây (10 phút với loại có cửa sổ dài; tối đa 10.000 khóa,
 * vượt thì gộp về khóa {@code anomalyType}) và báo {@code suppressed=N} ở dòng kế tiếp. Chỉ ghi log máy, không ghi DB.
 */
public class PaymentAnomalyLogAdapter implements PaymentAnomalyLogPort {

    static final Duration SHORT_WINDOW = Duration.ofSeconds(60);
    static final Duration LONG_WINDOW = Duration.ofMinutes(10);
    static final int MAX_KEYS = 10_000;
    private static final String INBOUND_SOURCE = "webhook";

    private final LogThrottle throttle;
    private final Map<String, IntegrationLog> loggers = new ConcurrentHashMap<>();

    public PaymentAnomalyLogAdapter(Clock clock) {
        this.throttle = new LogThrottle(clock, SHORT_WINDOW, MAX_KEYS);
    }

    private IntegrationLog logger(LogChannel channel, String source) {
        return loggers.computeIfAbsent(channel.name() + ":" + source, k -> IntegrationLog.of(channel, source));
    }

    static LogChannel channelOf(PaymentAnomaly a) {
        return switch (a.type().channel()) {
            case INBOUND -> LogChannel.INTEGRATION_INBOUND;
            case OUTBOUND -> LogChannel.INTEGRATION_OUTBOUND;
            case SOURCE -> a.source() == PaymentAnomalySource.POLL ? LogChannel.INTEGRATION_OUTBOUND
                    : LogChannel.INTEGRATION_INBOUND;
        };
    }

    private static String opOf(LogChannel channel, PaymentAnomalySource source) {
        if (channel == LogChannel.INTEGRATION_OUTBOUND) {
            return "queryPayment";
        }
        return source == PaymentAnomalySource.SIMULATE_BUTTON ? "simulatePaid" : "webhook";
    }

    @Override
    public void log(PaymentAnomaly a) {
        PaymentAnomalyType type = a.type();
        LogChannel channel = channelOf(a);
        String loggerSource = channel == LogChannel.INTEGRATION_OUTBOUND ? a.provider() : INBOUND_SOURCE;
        IntegrationLog log = logger(channel, loggerSource);
        boolean error = type.severity() == PaymentAnomalyType.Severity.ERROR;
        if (error ? !log.isErrorEnabled() : !log.isWarnEnabled()) {
            return;
        }
        String subject = a.intentId() != null ? a.intentId().toString() : String.valueOf(a.provider());
        LogThrottle.Decision d = throttle.acquire(type.name() + ":" + subject, type.name(),
                type.longThrottleWindow() ? LONG_WINDOW : SHORT_WINDOW);
        if (!d.emit()) {
            return;
        }
        IntegrationLog.Entry e = log.entry().provider(a.provider()).op(opOf(channel, a.source()))
                .tenantId(a.tenantId()).intentId(a.intentId()).reference(a.reference())
                .outcome(channel == LogChannel.INTEGRATION_OUTBOUND ? "FAIL" : "REJECTED")
                .errorCode(a.errorCode()).errorKind(a.errorKind()).errorClass(a.errorClass())
                .partnerCode(a.partnerCode()).retryAfter(a.retryAfter()).suppressed(d.suppressed())
                .anomaly(type.name());
        if (a.source() != null) {
            e.source(a.source().name());
        }
        if (a.httpStatus() != null) {
            e.httpStatus(a.httpStatus());
        }
        if (a.durationMs() != null) {
            e.durationMs(a.durationMs());
        }
        if (a.failures() != null) {
            e.failures(a.failures());
        }
        if (a.bodyLength() != null) {
            e.bodyLength(a.bodyLength());
        }
        if (a.signaturePresent() != null) {
            e.signaturePresent(a.signaturePresent());
        }
        if (a.clientIp() != null) {
            e.clientIp(a.clientIp());
        }
        if (error) {
            e.error();
        } else {
            e.warn();
        }
    }

    @Override
    public void recovered(String provider, int failures) {
        logger(LogChannel.INTEGRATION_OUTBOUND, provider).entry().provider(provider).op("queryPayment")
                .outcome("RECOVERED").failures(failures).info();
    }

    @Override
    public void conflict(PaymentAnomalySource source, String provider, UUID tenantId, UUID intentId,
                         String reference) {
        LogChannel channel = source == PaymentAnomalySource.POLL ? LogChannel.INTEGRATION_OUTBOUND
                : LogChannel.INTEGRATION_INBOUND;
        IntegrationLog log = logger(channel, channel == LogChannel.INTEGRATION_OUTBOUND ? provider : INBOUND_SOURCE);
        if (!log.isInfoEnabled()) {
            return;
        }
        String subject = intentId != null ? intentId.toString() : String.valueOf(provider);
        LogThrottle.Decision d = throttle.acquire("CONFLICT:" + subject, "CONFLICT", SHORT_WINDOW);
        if (!d.emit()) {
            return;
        }
        IntegrationLog.Entry e = log.entry().provider(provider).op(opOf(channel, source)).tenantId(tenantId)
                .intentId(intentId).reference(reference).outcome("CONFLICT").suppressed(d.suppressed());
        if (source != null) {
            e.source(source.name());
        }
        e.info();
    }
}
