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
import lms.sales.ordering.application.PaymentTenantResolverPort;
import lms.sales.ordering.application.ProcessPaymentWebhookUseCase;
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
 */
@Component
public class SimulatedWebhookProcessor {

    public static final String SIGNATURE_HEADER = "X-Signature";
    private static final String ALGORITHM = "HmacSHA256";
    private static final int MAX_BODY = 4096;

    private final ProcessPaymentWebhookUseCase useCase;
    private final PaymentTenantResolverPort tenants;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final String secret;

    public SimulatedWebhookProcessor(ProcessPaymentWebhookUseCase useCase, PaymentTenantResolverPort tenants,
                                     ObjectMapper mapper,
                                     @Value("${app.payments.sandbox.enabled:false}") boolean enabled,
                                     @Value("${app.payments.sandbox.webhook-secret:}") String secret) {
        this.useCase = useCase;
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

    public ProcessPaymentWebhookUseCase.Result process(String rawBody, String signatureHeader) {
        requireEnabled();
        if (rawBody == null || rawBody.length() > MAX_BODY) {
            throw SalesException.invalidRequest("Thân yêu cầu không hợp lệ");
        }
        if (!signatureMatches(rawBody, signatureHeader)) {
            throw new SalesException(ErrorCode.INVALID_WEBHOOK_SIGNATURE, "Chữ ký không hợp lệ");
        }
        ProcessPaymentWebhookUseCase.Command cmd = parse(rawBody);
        UUID tenantId = tenants.findTenantIdByReference(cmd.reference()).orElseThrow(
                () -> new SalesException(ErrorCode.PAYMENT_INTENT_NOT_FOUND, "Không có lệnh thanh toán"));
        return TenantContext.callAs(tenantId, () -> useCase.execute(tenantId, cmd));
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
