package lms.sales.ordering.infrastructure.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lms.observability.log.IntegrationLog;
import lms.observability.log.LogChannel;
import lms.sales.ordering.application.ApplyGatewayPaymentUseCase;
import lms.sales.ordering.application.PaymentAnomaly;
import lms.sales.ordering.application.PaymentAnomalySource;
import lms.sales.ordering.application.PaymentAnomalyType;
import lms.sales.ordering.application.PaymentTenantResolverPort;
import lms.sales.ordering.application.ProcessPaymentWebhookUseCase;
import lms.sales.ordering.application.RecordPaymentAnomalyUseCase;
import lms.sales.ordering.domain.PaymentIntent;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Luồng nhận kết quả thanh toán của cổng GIẢ LẬP (ADR-0020), dùng chung cho webhook và nút "giả lập đã thanh toán".
 * Thứ tự bắt buộc: (1) sandbox bật, (2) xác thực chữ ký HMAC-SHA256 trên thân thô bằng so sánh hằng thời gian, (3) mới
 * phân tích JSON và tra tenant theo mã tham chiếu, (4) đặt TenantContext theo tenant của lệnh rồi gọi use case (transaction
 * mở sau khi đã có tenant nên RLS đúng). Chưa qua chữ ký thì không tra DB, nên không dò được mã tham chiếu.
 *
 * <p>Log kênh INTEGRATION_INBOUND (ADR-0029): bất thường trước khi biết tenant (thân hỏng, chữ ký sai, mã lạ) chỉ log
 * máy; sau khi biết tenant, ghi nhận đi qua {@link ApplyGatewayPaymentUseCase} (phân loại bất thường tiền, audit ở
 * transaction riêng). Không bao giờ log chữ ký, thân thô hay token: chỉ {@code signaturePresent}, {@code bodyLength} và
 * IP đã che.
 */
@Component
public class SimulatedWebhookProcessor {

    public static final String SIGNATURE_HEADER = "X-Signature";
    private static final String ALGORITHM = "HmacSHA256";
    private static final int MAX_BODY = 4096;
    private static final IntegrationLog INBOUND = IntegrationLog.of(LogChannel.INTEGRATION_INBOUND, "webhook");

    private final ApplyGatewayPaymentUseCase useCase;
    private final RecordPaymentAnomalyUseCase anomalies;
    private final PaymentTenantResolverPort tenants;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final String secret;

    public SimulatedWebhookProcessor(ApplyGatewayPaymentUseCase useCase, RecordPaymentAnomalyUseCase anomalies,
                                     PaymentTenantResolverPort tenants, ObjectMapper mapper,
                                     @Value("${app.payments.sandbox.enabled:false}") boolean enabled,
                                     @Value("${app.payments.sandbox.webhook-secret:}") String secret) {
        this.useCase = useCase;
        this.anomalies = anomalies;
        this.tenants = tenants;
        this.mapper = mapper;
        this.enabled = enabled;
        this.secret = secret == null ? "" : secret;
    }

    /** Sandbox bật và có bí mật ký; tắt thì mọi đường giả lập trả 404. */
    public boolean isEnabled() {
        return enabled && !secret.isBlank();
    }

    public void requireEnabled() {
        if (!isEnabled()) {
            throw new SalesException(ErrorCode.PAYMENT_SANDBOX_DISABLED, "Cổng thanh toán giả lập chưa được bật");
        }
    }

    /** Chữ ký (hex thường) của thân yêu cầu. */
    public String sign(String body) {
        requireEnabled();
        return hmacHex(body.getBytes(StandardCharsets.UTF_8));
    }

    /** Webhook thường: nguồn {@code WEBHOOK}, không biết IP. */
    public ProcessPaymentWebhookUseCase.Result process(String rawBody, String signatureHeader) {
        return process(rawBody, signatureHeader, PaymentAnomalySource.WEBHOOK, null);
    }

    /**
     * @param origin {@code WEBHOOK} hoặc {@code SIMULATE_BUTTON}
     * @param clientIp địa chỉ gọi đến (chỉ để log, được che trước khi ghi); null nếu không có
     */
    public ProcessPaymentWebhookUseCase.Result process(String rawBody, String signatureHeader,
                                                       PaymentAnomalySource origin, String clientIp) {
        requireEnabled();
        long start = System.nanoTime();
        int bodyLength = rawBody == null ? 0 : rawBody.length();
        if (rawBody == null || bodyLength > MAX_BODY) {
            // Thân rỗng đi tiếp tới kiểm chữ ký như trước (giữ nguyên mã lỗi HTTP); thân quá lớn thì dừng ngay
            report(PaymentAnomalyType.WEBHOOK_MALFORMED, origin, null, bodyLength, signatureHeader, clientIp);
            throw SalesException.invalidRequest("Thân yêu cầu không hợp lệ");
        }
        if (!signatureMatches(rawBody, signatureHeader)) {
            report(PaymentAnomalyType.WEBHOOK_BAD_SIGNATURE, origin, null, bodyLength, signatureHeader, clientIp);
            throw new SalesException(ErrorCode.INVALID_WEBHOOK_SIGNATURE, "Chữ ký không hợp lệ");
        }
        ProcessPaymentWebhookUseCase.Command cmd;
        try {
            cmd = parse(rawBody);
        } catch (SalesException e) {
            report(PaymentAnomalyType.WEBHOOK_MALFORMED, origin, null, bodyLength, signatureHeader, clientIp);
            throw e;
        }
        UUID tenantId = tenants.findTenantIdByReference(cmd.reference()).orElse(null);
        if (tenantId == null) {
            report(PaymentAnomalyType.WEBHOOK_UNKNOWN_REFERENCE, origin, cmd.reference(), bodyLength,
                    signatureHeader, clientIp);
            throw new SalesException(ErrorCode.PAYMENT_INTENT_NOT_FOUND, "Không có lệnh thanh toán");
        }
        try {
            ProcessPaymentWebhookUseCase.Result result = TenantContext.callAs(tenantId,
                    () -> useCase.apply(tenantId, cmd, PaymentIntent.PROVIDER_SIMULATED, origin));
            logResult(origin, tenantId, cmd.reference(), result, start);
            return result;
        } catch (SalesException e) {
            logRejected(origin, tenantId, cmd.reference(), e, start);
            throw e;
        }
    }

    private void report(PaymentAnomalyType type, PaymentAnomalySource origin, String reference, int bodyLength,
                        String signatureHeader, String clientIp) {
        ErrorCode code = type == PaymentAnomalyType.WEBHOOK_BAD_SIGNATURE ? ErrorCode.INVALID_WEBHOOK_SIGNATURE
                : type == PaymentAnomalyType.WEBHOOK_UNKNOWN_REFERENCE ? ErrorCode.PAYMENT_INTENT_NOT_FOUND
                : ErrorCode.INVALID_REQUEST;
        anomalies.report(PaymentAnomaly.of(type, origin, PaymentIntent.PROVIDER_SIMULATED).reference(reference)
                .bodyLength(bodyLength).signaturePresent(signatureHeader != null && !signatureHeader.isBlank())
                .clientIp(clientIp).errorCode(code.name()).build());
    }

    private void logResult(PaymentAnomalySource origin, UUID tenantId, String reference,
                           ProcessPaymentWebhookUseCase.Result result, long startNanos) {
        if (!INBOUND.isInfoEnabled()) {
            return;
        }
        String outcome = result.ignored() ? "IGNORED" : result.duplicate() ? "DUPLICATE" : "PROCESSED";
        INBOUND.entry().provider(PaymentIntent.PROVIDER_SIMULATED).op(opOf(origin)).tenantId(tenantId)
                .intentId(result.intent().id()).reference(reference).outcome(outcome).httpStatus(200)
                .durationMs((System.nanoTime() - startNanos) / 1_000_000L).source(origin.name()).info();
    }

    private void logRejected(PaymentAnomalySource origin, UUID tenantId, String reference, SalesException e,
                             long startNanos) {
        // Xung đột phiên bản/đồng thời đã có dòng CONFLICT (hoặc dòng bất thường DOUBLE_PAID) do ApplyGatewayPaymentUseCase ghi
        if (e.code() == ErrorCode.PAYMENT_INTENT_STATE_CONFLICT || !INBOUND.isWarnEnabled()) {
            return;
        }
        INBOUND.entry().provider(PaymentIntent.PROVIDER_SIMULATED).op(opOf(origin)).tenantId(tenantId)
                .reference(reference).outcome("REJECTED").httpStatus(e.code().httpStatus())
                .errorCode(e.code().name()).durationMs((System.nanoTime() - startNanos) / 1_000_000L)
                .source(origin.name()).warn();
    }

    private static String opOf(PaymentAnomalySource origin) {
        return origin == PaymentAnomalySource.SIMULATE_BUTTON ? "simulatePaid" : "webhook";
    }

    private boolean signatureMatches(String rawBody, String header) {
        if (header == null || header.isBlank()) {
            return false;
        }
        String provided = header.trim().toLowerCase(Locale.ROOT);
        if (provided.startsWith("sha256=")) {
            provided = provided.substring("sha256=".length());
        }
        byte[] expected = hmacHex(rawBody.getBytes(StandardCharsets.UTF_8)).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, provided.getBytes(StandardCharsets.US_ASCII));
    }

    private String hmacHex(byte[] data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(data));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Không tính được HMAC", e);
        }
    }

    private ProcessPaymentWebhookUseCase.Command parse(String rawBody) {
        JsonNode n;
        try {
            n = mapper.readTree(rawBody);
        } catch (Exception e) {
            throw SalesException.invalidRequest("Thân yêu cầu không phải JSON hợp lệ");
        }
        String reference = text(n, "reference");
        String txn = text(n, "providerTxnId");
        String status = text(n, "status");
        JsonNode amount = n == null ? null : n.get("amount");
        if (reference == null || reference.length() > 40 || status == null
                || (txn != null && txn.length() > 100) || amount == null || !amount.canConvertToLong()
                || amount.asLong() < 0) {
            throw SalesException.invalidRequest("Thiếu hoặc sai reference, amount, providerTxnId, status");
        }
        return new ProcessPaymentWebhookUseCase.Command(reference.trim(), amount.asLong(), txn, status);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() || !v.isTextual() || v.asText().isBlank() ? null : v.asText();
    }
}
