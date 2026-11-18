package lms.observability.log;

import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Bản ghi log tích hợp dạng {@code key=value} theo thứ tự trường cố định (ADR-0029 D3). Cố ý KHÔNG có phương thức nhận
 * {@code Map} hay chuỗi tự do: chỉ các trường trong danh sách trắng bên dưới, mỗi trường tự che/lọc theo kiểu dữ liệu,
 * nên muốn log thân request, token hay thông điệp đối tác thì phải sửa lớp này (lộ ra khi review).
 *
 * <p>Một {@code IntegrationLog} gắn với một logger (kênh + nguồn). Chuỗi chỉ được dựng khi mức log tương ứng đang bật.
 * Trường lõi luôn có mặt (thiếu thì {@code -}): provider, op, cid, tenantId, intentId, ref, outcome, httpStatus,
 * durationMs, errorCode, errorKind, errorClass, partnerCode, retryAfter, suppressed, anomaly, anomalyType. Trường phụ
 * (source, failures, signaturePresent, bodyLength, clientIp) chỉ có khi được đặt.
 */
public final class IntegrationLog {

    private static final Pattern PARTNER_CODE = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final Pattern DIGITS = Pattern.compile("\\d{1,6}");
    private static final String CORRELATION_MDC_KEY = "correlationId";

    private final LogChannel channel;
    private final Logger logger;

    private IntegrationLog(LogChannel channel, Logger logger) {
        this.channel = channel;
        this.logger = logger;
    }

    /** {@code source} là hậu tố logger: nhà cung cấp (kênh gọi ra) hoặc nguồn (kênh gọi vào). */
    public static IntegrationLog of(LogChannel channel, String source) {
        return new IntegrationLog(channel, LoggerFactory.getLogger(channel.loggerName(source)));
    }

    /** Dùng logger có sẵn (test hoặc nơi cần tên logger riêng). */
    static IntegrationLog of(LogChannel channel, Logger logger) {
        return new IntegrationLog(channel, logger);
    }

    public LogChannel channel() {
        return channel;
    }

    public String loggerName() {
        return logger.getName();
    }

    public boolean isDebugEnabled() {
        return logger.isDebugEnabled();
    }

    public boolean isInfoEnabled() {
        return logger.isInfoEnabled();
    }

    public boolean isWarnEnabled() {
        return logger.isWarnEnabled();
    }

    public boolean isErrorEnabled() {
        return logger.isErrorEnabled();
    }

    /** Bắt đầu một bản ghi mới. */
    public Entry entry() {
        return new Entry();
    }

    /** Bản ghi đang dựng; mọi setter che/lọc giá trị ngay khi nhận. */
    public final class Entry {

        private String provider;
        private String op;
        private String cid;
        private String tenantId;
        private String intentId;
        private String ref;
        private String outcome;
        private Integer httpStatus;
        private Long durationMs;
        private String errorCode;
        private String errorKind;
        private String errorClass;
        private String partnerCode;
        private String retryAfter;
        private int suppressed;
        private String anomalyType;
        private String source;
        private Integer failures;
        private Boolean signaturePresent;
        private Integer bodyLength;
        private String clientIp;

        private Entry() {
        }

        public Entry provider(String v) {
            this.provider = LogMask.safe(v);
            return this;
        }

        public Entry op(String v) {
            this.op = LogMask.safe(v);
            return this;
        }

        /** Mặc định lấy MDC {@code correlationId} khi ghi; chỉ đặt tay khi không có request (job). */
        public Entry cid(String v) {
            this.cid = LogMask.safe(v);
            return this;
        }

        public Entry tenantId(UUID v) {
            this.tenantId = v == null ? null : v.toString();
            return this;
        }

        public Entry intentId(UUID v) {
            this.intentId = v == null ? null : v.toString();
            return this;
        }

        /** Mã tham chiếu thô; bị che theo {@link LogMask#reference(String)}. */
        public Entry reference(String raw) {
            this.ref = raw == null ? null : LogMask.reference(raw);
            return this;
        }

        public Entry outcome(String v) {
            this.outcome = LogMask.safe(v);
            return this;
        }

        public Entry httpStatus(int v) {
            this.httpStatus = v;
            return this;
        }

        public Entry durationMs(long v) {
            this.durationMs = v;
            return this;
        }

        public Entry errorCode(String v) {
            this.errorCode = LogMask.safe(v);
            return this;
        }

        public Entry errorKind(String v) {
            this.errorKind = LogMask.safe(v);
            return this;
        }

        /** Chỉ tên lớp (getSimpleName) của nguyên nhân gốc; không bao giờ thông điệp hay stack trace. */
        public Entry errorClass(Throwable t) {
            if (t == null) {
                return this;
            }
            Throwable root = t;
            for (int i = 0; i < 16 && root.getCause() != null && root.getCause() != root; i++) {
                root = root.getCause();
            }
            this.errorClass = LogMask.safe(root.getClass().getSimpleName());
            return this;
        }

        public Entry errorClass(String simpleName) {
            this.errorClass = LogMask.safe(simpleName);
            return this;
        }

        /** Mã kết quả của đối tác; chỉ nhận {@code [A-Za-z0-9_-]{1,32}}, giá trị khác bị bỏ. */
        public Entry partnerCode(String v) {
            this.partnerCode = v != null && PARTNER_CODE.matcher(v).matches() ? v : null;
            return this;
        }

        /** Giá trị Retry-After dạng số giây; giá trị khác bị bỏ. */
        public Entry retryAfter(String v) {
            this.retryAfter = v != null && DIGITS.matcher(v.trim()).matches() ? v.trim() : null;
            return this;
        }

        public Entry suppressed(int v) {
            this.suppressed = Math.max(0, v);
            return this;
        }

        /** Đánh dấu bất thường: {@code anomaly=true anomalyType=<type>}. */
        public Entry anomaly(String type) {
            this.anomalyType = LogMask.safe(type);
            return this;
        }

        /** Nguồn kích hoạt (POLL, WEBHOOK, SIMULATE_BUTTON). */
        public Entry source(String v) {
            this.source = LogMask.safe(v);
            return this;
        }

        public Entry failures(int v) {
            this.failures = v;
            return this;
        }

        public Entry signaturePresent(boolean v) {
            this.signaturePresent = v;
            return this;
        }

        public Entry bodyLength(int v) {
            this.bodyLength = v;
            return this;
        }

        /** IP người gọi thô; bị che theo {@link LogMask#clientIp(String)}. */
        public Entry clientIp(String raw) {
            this.clientIp = raw == null ? null : LogMask.clientIp(raw);
            return this;
        }

        public void debug() {
            if (logger.isDebugEnabled()) {
                logger.debug(render());
            }
        }

        public void info() {
            if (logger.isInfoEnabled()) {
                logger.info(render());
            }
        }

        public void warn() {
            if (logger.isWarnEnabled()) {
                logger.warn(render());
            }
        }

        public void error() {
            if (logger.isErrorEnabled()) {
                logger.error(render());
            }
        }

        private String render() {
            StringBuilder sb = new StringBuilder(256);
            sb.append(channel.eventToken());
            kv(sb, "channel", channel.name());
            kv(sb, "provider", provider);
            kv(sb, "op", op);
            kv(sb, "cid", cid != null ? cid : LogMask.safe(MDC.get(CORRELATION_MDC_KEY)));
            kv(sb, "tenantId", tenantId);
            kv(sb, "intentId", intentId);
            kv(sb, "ref", ref);
            kv(sb, "outcome", outcome);
            kv(sb, "httpStatus", httpStatus == null ? null : httpStatus.toString());
            kv(sb, "durationMs", durationMs == null ? null : durationMs.toString());
            kv(sb, "errorCode", errorCode);
            kv(sb, "errorKind", errorKind);
            kv(sb, "errorClass", errorClass);
            kv(sb, "partnerCode", partnerCode);
            kv(sb, "retryAfter", retryAfter);
            kv(sb, "suppressed", Integer.toString(suppressed));
            kv(sb, "anomaly", Boolean.toString(anomalyType != null));
            kv(sb, "anomalyType", anomalyType);
            optional(sb, "source", source);
            optional(sb, "failures", failures == null ? null : failures.toString());
            optional(sb, "signaturePresent", signaturePresent == null ? null : signaturePresent.toString());
            optional(sb, "bodyLength", bodyLength == null ? null : bodyLength.toString());
            optional(sb, "clientIp", clientIp);
            return sb.toString();
        }

        private void kv(StringBuilder sb, String key, String value) {
            sb.append(' ').append(key).append('=').append(value == null || value.isEmpty() ? "-" : value);
        }

        private void optional(StringBuilder sb, String key, String value) {
            if (value != null) {
                sb.append(' ').append(key).append('=').append(value);
            }
        }
    }
}
