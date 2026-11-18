package lms.sales.ordering.application;

/**
 * Danh sách ĐÓNG các loại bất thường của luồng thanh toán cổng (ADR-0029 D4, tài liệu thiết kế mục 4.3). Mỗi loại
 * quyết định: kênh log, mức log, có ghi audit {@code PAYMENT_ANOMALY} hay không (chỉ bất thường về tiền mà cửa hàng
 * phải xử lý tay), và cửa sổ giảm trùng log. Thêm loại mới = sửa file này (và bảng trong tài liệu thiết kế).
 */
public enum PaymentAnomalyType {

    PAYMENT_AMOUNT_MISMATCH(Channel.SOURCE, Severity.WARN, true, false),
    PAYMENT_AFTER_EXPIRY(Channel.SOURCE, Severity.WARN, true, false),
    PAYMENT_AFTER_CANCEL(Channel.SOURCE, Severity.WARN, true, false),
    PAYMENT_DOUBLE_PAID(Channel.SOURCE, Severity.ERROR, true, false),
    PAYMENT_TXN_REUSED_OTHER_INTENT(Channel.SOURCE, Severity.ERROR, true, false),
    PAYMENT_PROVIDER_MISMATCH(Channel.INBOUND, Severity.WARN, true, false),
    WEBHOOK_BAD_SIGNATURE(Channel.INBOUND, Severity.WARN, false, false),
    WEBHOOK_MALFORMED(Channel.INBOUND, Severity.WARN, false, false),
    WEBHOOK_UNKNOWN_REFERENCE(Channel.INBOUND, Severity.WARN, false, false),
    GATEWAY_AUTH_REJECTED(Channel.OUTBOUND, Severity.ERROR, false, true),
    GATEWAY_RATE_LIMITED(Channel.OUTBOUND, Severity.WARN, false, false),
    GATEWAY_POLL_FAILING_REPEATEDLY(Channel.OUTBOUND, Severity.ERROR, false, true),
    GATEWAY_UNEXPECTED_RESPONSE(Channel.OUTBOUND, Severity.WARN, false, false);

    /** Kênh log của bất thường; {@code SOURCE} theo đường kích hoạt (thăm dò là OUTBOUND, webhook/nút là INBOUND). */
    public enum Channel { SOURCE, INBOUND, OUTBOUND }

    public enum Severity { WARN, ERROR }

    private final Channel channel;
    private final Severity severity;
    private final boolean audited;
    private final boolean longThrottleWindow;

    PaymentAnomalyType(Channel channel, Severity severity, boolean audited, boolean longThrottleWindow) {
        this.channel = channel;
        this.severity = severity;
        this.audited = audited;
        this.longThrottleWindow = longThrottleWindow;
    }

    public Channel channel() {
        return channel;
    }

    public Severity severity() {
        return severity;
    }

    /** Có ghi audit {@code PAYMENT_ANOMALY} (khi biết tenant) hay chỉ log máy. */
    public boolean audited() {
        return audited;
    }

    /** Cửa sổ giảm trùng log dài (10 phút) thay vì 60 giây. */
    public boolean longThrottleWindow() {
        return longThrottleWindow;
    }
}
