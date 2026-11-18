package lms.sales.ordering.application;

import java.util.UUID;

/**
 * Dữ liệu một bất thường thanh toán (ADR-0029), không chứa PII: không tên/điện thoại/email/nội dung chuyển khoản, không
 * thân thô, không token. {@code reference} và {@code providerTxnId} là bản đầy đủ (chỉ audit có RLS dùng nguyên văn);
 * adapter log tự che trước khi ghi log máy. Trường tùy chọn có thể null.
 */
public record PaymentAnomaly(PaymentAnomalyType type, PaymentAnomalySource source, String provider, UUID tenantId,
                             UUID intentId, UUID orderId, String reference, Long expectedAmount, Long receivedAmount,
                             String providerTxnId, String intentStatus, String errorCode, String errorKind,
                             String errorClass, Integer httpStatus, Long durationMs, String retryAfter,
                             String partnerCode, Integer failures, Integer bodyLength, Boolean signaturePresent,
                             String clientIp) {

    public PaymentAnomaly {
        if (type == null) {
            throw new IllegalArgumentException("type bắt buộc");
        }
    }

    public static Builder of(PaymentAnomalyType type, PaymentAnomalySource source, String provider) {
        return new Builder(type, source, provider);
    }

    public static final class Builder {
        private final PaymentAnomalyType type;
        private final PaymentAnomalySource source;
        private final String provider;
        private UUID tenantId;
        private UUID intentId;
        private UUID orderId;
        private String reference;
        private Long expectedAmount;
        private Long receivedAmount;
        private String providerTxnId;
        private String intentStatus;
        private String errorCode;
        private String errorKind;
        private String errorClass;
        private Integer httpStatus;
        private Long durationMs;
        private String retryAfter;
        private String partnerCode;
        private Integer failures;
        private Integer bodyLength;
        private Boolean signaturePresent;
        private String clientIp;

        private Builder(PaymentAnomalyType type, PaymentAnomalySource source, String provider) {
            this.type = type;
            this.source = source;
            this.provider = provider;
        }

        public Builder tenantId(UUID v) { this.tenantId = v; return this; }
        public Builder intentId(UUID v) { this.intentId = v; return this; }
        public Builder orderId(UUID v) { this.orderId = v; return this; }
        public Builder reference(String v) { this.reference = v; return this; }
        public Builder expectedAmount(Long v) { this.expectedAmount = v; return this; }
        public Builder receivedAmount(Long v) { this.receivedAmount = v; return this; }
        public Builder providerTxnId(String v) { this.providerTxnId = v; return this; }
        public Builder intentStatus(String v) { this.intentStatus = v; return this; }
        public Builder errorCode(String v) { this.errorCode = v; return this; }
        public Builder errorKind(String v) { this.errorKind = v; return this; }
        public Builder errorClass(String v) { this.errorClass = v; return this; }
        public Builder httpStatus(Integer v) { this.httpStatus = v; return this; }
        public Builder durationMs(Long v) { this.durationMs = v; return this; }
        public Builder retryAfter(String v) { this.retryAfter = v; return this; }
        public Builder partnerCode(String v) { this.partnerCode = v; return this; }
        public Builder failures(Integer v) { this.failures = v; return this; }
        public Builder bodyLength(Integer v) { this.bodyLength = v; return this; }
        public Builder signaturePresent(Boolean v) { this.signaturePresent = v; return this; }
        public Builder clientIp(String v) { this.clientIp = v; return this; }

        public PaymentAnomaly build() {
            return new PaymentAnomaly(type, source, provider, tenantId, intentId, orderId, reference, expectedAmount,
                    receivedAmount, providerTxnId, intentStatus, errorCode, errorKind, errorClass, httpStatus,
                    durationMs, retryAfter, partnerCode, failures, bodyLength, signaturePresent, clientIp);
        }
    }
}
