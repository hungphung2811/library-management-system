package lms.sales.ordering.infrastructure.client;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import lms.observability.log.IntegrationLog;
import lms.observability.log.LogChannel;
import lms.observability.log.LogThrottle;
import lms.sales.ordering.application.PaymentAnomaly;
import lms.sales.ordering.application.PaymentAnomalyLogPort;
import lms.sales.ordering.application.PaymentAnomalySource;
import lms.sales.ordering.application.PaymentAnomalyType;
import lms.sales.shared.domain.ErrorCode;
import lms.tenant.TenantContext;

/**
 * Ghi log kênh INTEGRATION_OUTBOUND cho một cổng thanh toán (ADR-0029 D6), dùng chung cho SePay và MoMo. Quy tắc mức log:
 * thăm dò không có giao dịch DEBUG; khớp giao dịch và {@code createQr} INFO; lỗi gọi cổng WARN kèm
 * {@code errorKind}/{@code errorClass} (giảm trùng 60 giây theo cổng + loại lỗi); 401/403, 429 và phản hồi lạ đi qua
 * {@link PaymentAnomalyLogPort} (một dòng có {@code anomaly=true}, thay cho dòng WARN thường); lỗi liên tiếp do
 * {@link GatewayHealthTracker}. KHÔNG nhận thông điệp lỗi, URL, header hay thân: chỉ kiểu lỗi, mã HTTP, mã đối tác dạng
 * ký tự an toàn và tên lớp exception gốc.
 */
public class GatewayCallLogger {

    private static final PaymentAnomalyLogPort NO_PORT = new PaymentAnomalyLogPort() {
        @Override
        public void log(PaymentAnomaly anomaly) {
        }

        @Override
        public void recovered(String provider, int failures) {
        }

        @Override
        public void conflict(PaymentAnomalySource source, String provider, UUID tenantId, UUID intentId,
                             String reference) {
        }
    };

    private final String provider;
    private final IntegrationLog out;
    private final LogThrottle throttle;
    private final PaymentAnomalyLogPort anomalies;
    private final GatewayHealthTracker health;

    public GatewayCallLogger(String provider, Clock clock, PaymentAnomalyLogPort anomalies,
                             GatewayHealthTracker health) {
        this.provider = provider;
        this.out = IntegrationLog.of(LogChannel.INTEGRATION_OUTBOUND, provider);
        this.throttle = new LogThrottle(clock, Duration.ofSeconds(60), 10_000);
        this.anomalies = anomalies;
        this.health = health;
    }

    /** Bản không báo bất thường và không theo dõi sức khỏe (dùng khi dựng cổng tay, vd test); vẫn ghi log kênh OUTBOUND. */
    public static GatewayCallLogger standalone(String provider) {
        return new GatewayCallLogger(provider, Clock.systemUTC(), NO_PORT, new GatewayHealthTracker(Clock.systemUTC(),
                NO_PORT));
    }

    /** Một lần tạo mã thanh toán (không gọi mạng hoặc gọi thành công): INFO một dòng cho mỗi lệnh. */
    public void created(String reference, long durationMs, Integer httpStatus) {
        if (out.isInfoEnabled()) {
            var e = base("createQr", reference, "OK", durationMs, httpStatus);
            e.info();
        }
    }

    /** Cổng trả lời hợp lệ nhưng chưa có giao dịch khớp: DEBUG, và đánh dấu cổng khỏe. */
    public void noMatch(String reference, long durationMs, int httpStatus, String partnerCode) {
        health.onSuccess(provider);
        if (out.isDebugEnabled()) {
            base("queryPayment", reference, "NO_MATCH", durationMs, httpStatus).partnerCode(partnerCode).debug();
        }
    }

    /** Khớp một giao dịch (sắp ghi nhận PAID): INFO. */
    public void matched(String reference, long durationMs, int httpStatus) {
        health.onSuccess(provider);
        base("queryPayment", reference, "MATCHED", durationMs, httpStatus).info();
    }

    /** Cổng từ chối tạo giao dịch bằng mã nghiệp vụ của đối tác (cổng vẫn khỏe): WARN giảm trùng. */
    public void rejected(String op, String reference, long durationMs, int httpStatus, String partnerCode) {
        health.onSuccess(provider);
        warnThrottled(op, reference, durationMs, httpStatus, "REJECTED", null, partnerCode, null);
    }

    /**
     * Lỗi gọi cổng. {@code kind} lấy từ {@link GatewayErrors}; {@code unexpected} = nhận 2xx nhưng nội dung không dùng
     * được. Lỗi của thao tác dò ({@code queryPayment}) được tính vào bộ đếm lỗi liên tiếp.
     */
    public void failed(String op, String reference, long durationMs, Integer httpStatus, String kind, Throwable cause,
                       String partnerCode, String retryAfter, boolean unexpected) {
        if ("queryPayment".equals(op)) {
            health.onFailure(provider, kind);
        }
        PaymentAnomalyType type = unexpected ? PaymentAnomalyType.GATEWAY_UNEXPECTED_RESPONSE
                : GatewayErrors.HTTP_AUTH.equals(kind) ? PaymentAnomalyType.GATEWAY_AUTH_REJECTED
                : GatewayErrors.HTTP_RATE_LIMIT.equals(kind) ? PaymentAnomalyType.GATEWAY_RATE_LIMITED : null;
        String errorClass = cause == null ? null : rootSimpleName(cause);
        if (type != null) {
            anomalies.log(PaymentAnomaly.of(type, PaymentAnomalySource.POLL, provider).reference(reference)
                    .tenantId(TenantContext.current().orElse(null))
                    .errorCode(ErrorCode.PAYMENT_GATEWAY_ERROR.name()).errorKind(kind).errorClass(errorClass)
                    .httpStatus(httpStatus).durationMs(durationMs).retryAfter(retryAfter).partnerCode(partnerCode)
                    .build());
            return;
        }
        warnThrottled(op, reference, durationMs, httpStatus, "FAIL", kind, partnerCode, cause);
    }

    private void warnThrottled(String op, String reference, long durationMs, Integer httpStatus, String outcome,
                               String kind, String partnerCode, Throwable cause) {
        if (!out.isWarnEnabled()) {
            return;
        }
        String key = provider + ":" + outcome + ":" + (kind == null ? partnerCode : kind);
        LogThrottle.Decision d = throttle.acquire(key, provider + ":" + outcome, Duration.ofSeconds(60));
        if (!d.emit()) {
            return;
        }
        var e = base(op, reference, outcome, durationMs, httpStatus).errorCode(ErrorCode.PAYMENT_GATEWAY_ERROR.name())
                .suppressed(d.suppressed()).partnerCode(partnerCode);
        if (kind != null) {
            e.errorKind(kind);
        }
        if (cause != null) {
            e.errorClass(cause);
        }
        e.warn();
    }

    private IntegrationLog.Entry base(String op, String reference, String outcome, long durationMs,
                                      Integer httpStatus) {
        var e = out.entry().provider(provider).op(op).reference(reference).outcome(outcome).durationMs(durationMs);
        // Gắn tenant của request đang gọi cổng: thiếu thì dòng log lưu với tenant_id NULL và RLS giấu khỏi admin tenant
        TenantContext.current().ifPresent(e::tenantId);
        if (httpStatus != null) {
            e.httpStatus(httpStatus);
        }
        return e;
    }

    private static String rootSimpleName(Throwable t) {
        Throwable root = t;
        for (int i = 0; i < 16 && root.getCause() != null && root.getCause() != root; i++) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName();
    }
}
