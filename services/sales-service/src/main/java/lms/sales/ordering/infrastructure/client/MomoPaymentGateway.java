package lms.sales.ordering.infrastructure.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lms.observability.log.LogMask;
import lms.sales.ordering.application.PaymentGatewayPort;
import lms.sales.ordering.domain.PaymentIntent;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;

/**
 * Cổng MoMo (môi trường test https://test-payment.momo.vn, API v2 captureWallet). Tạo đơn bằng
 * {@code POST /v2/gateway/api/create} rồi dùng {@code qrCodeUrl} làm nội dung mã QR để app MoMo test quét. Kết quả không
 * chờ IPN (máy dev không có địa chỉ công khai): {@link #queryPayment} gọi {@code /v2/gateway/api/query}, resultCode 0 là
 * đã thanh toán. Chữ ký HMAC-SHA256 hex trên chuỗi {@code key=value} nối bằng {@code &} theo thứ tự chữ cái, theo mã
 * mẫu chính thức của MoMo (github.com/momo-wallet/payment). Khóa sandbox lấy từ cấu hình, KHÔNG dùng cho production.
 */
public class MomoPaymentGateway implements PaymentGatewayPort {

    private final ObjectMapper json;
    private final HttpClient http;
    private final String endpoint;
    private final String partnerCode;
    private final String accessKey;
    private final String secretKey;
    private final String redirectUrl;
    private final String ipnUrl;
    private final GatewayCallLogger calls;

    public MomoPaymentGateway(ObjectMapper json, String endpoint, String partnerCode, String accessKey,
                              String secretKey, String redirectUrl, String ipnUrl) {
        this(json, endpoint, partnerCode, accessKey, secretKey, redirectUrl, ipnUrl,
                GatewayCallLogger.standalone(PaymentIntent.PROVIDER_MOMO));
    }

    public MomoPaymentGateway(ObjectMapper json, String endpoint, String partnerCode, String accessKey,
                              String secretKey, String redirectUrl, String ipnUrl, GatewayCallLogger calls) {
        this.calls = calls;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.partnerCode = partnerCode;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.redirectUrl = redirectUrl;
        this.ipnUrl = ipnUrl;
    }

    @Override
    public String provider() {
        return PaymentIntent.PROVIDER_MOMO;
    }

    @Override
    public QrCharge createQr(ChargeRequest request) {
        long amount = request.amount().amount();
        String requestId = UUID.randomUUID().toString();
        String orderInfo = "Thanh toan don POS " + request.reference();
        String raw = "accessKey=" + accessKey + "&amount=" + amount + "&extraData=" + "&ipnUrl=" + ipnUrl
                + "&orderId=" + request.reference() + "&orderInfo=" + orderInfo + "&partnerCode=" + partnerCode
                + "&redirectUrl=" + redirectUrl + "&requestId=" + requestId + "&requestType=captureWallet";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerCode", partnerCode);
        body.put("partnerName", "LMS");
        body.put("storeId", "LMS-POS");
        body.put("requestId", requestId);
        body.put("amount", amount);
        body.put("orderId", request.reference());
        body.put("orderInfo", orderInfo);
        body.put("redirectUrl", redirectUrl);
        body.put("ipnUrl", ipnUrl);
        body.put("lang", "vi");
        body.put("extraData", "");
        body.put("requestType", "captureWallet");
        body.put("signature", sign(raw));
        long start = System.nanoTime();
        Reply reply = post("/v2/gateway/api/create", body, "createQr", request.reference());
        JsonNode res = reply.body();
        if (!res.path("resultCode").canConvertToInt()) {
            calls.failed("createQr", request.reference(), millisSince(start), reply.status(), GatewayErrors.PARSE,
                    null, null, null, true);
        } else if (res.path("resultCode").asInt(-1) != 0) {
            calls.rejected("createQr", request.reference(), millisSince(start), reply.status(),
                    res.path("resultCode").asText(null));
        }
        if (res.path("resultCode").asInt(-1) != 0) {
            // Thông điệp của MoMo đi qua partnerMessage (bỏ email, dãy số dài, cắt 100 ký tự) vì trả về FE
            throw gatewayError("MoMo từ chối tạo giao dịch: "
                    + LogMask.partnerMessage(res.path("message").asText("không rõ lý do")), null);
        }
        String qr = res.path("qrCodeUrl").asText("");
        if (qr.isBlank()) {
            qr = res.path("payUrl").asText("");
        }
        if (qr.isBlank()) {
            throw gatewayError("MoMo không trả mã QR", null);
        }
        calls.created(request.reference(), millisSince(start), reply.status());
        return new QrCharge(qr);
    }

    @Override
    public Optional<GatewayPayment> queryPayment(String reference) {
        String requestId = UUID.randomUUID().toString();
        String raw = "accessKey=" + accessKey + "&orderId=" + reference + "&partnerCode=" + partnerCode
                + "&requestId=" + requestId;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerCode", partnerCode);
        body.put("requestId", requestId);
        body.put("orderId", reference);
        body.put("lang", "vi");
        body.put("signature", sign(raw));
        long start = System.nanoTime();
        Reply reply = post("/v2/gateway/api/query", body, "queryPayment", reference);
        JsonNode res = reply.body();
        if (!res.path("resultCode").canConvertToInt()) {
            calls.failed("queryPayment", reference, millisSince(start), reply.status(), GatewayErrors.PARSE, null, null,
                    null, true);
            return Optional.empty();
        }
        if (res.path("resultCode").asInt(-1) != 0) {
            calls.noMatch(reference, millisSince(start), reply.status(), res.path("resultCode").asText(null));
            return Optional.empty();
        }
        String txn = res.path("transId").asText("");
        if (txn.isBlank() || "0".equals(txn)) {
            calls.noMatch(reference, millisSince(start), reply.status(), res.path("resultCode").asText(null));
            return Optional.empty();
        }
        calls.matched(reference, millisSince(start), reply.status());
        return Optional.of(new GatewayPayment(true, "MOMO-" + txn, res.path("amount").asLong(-1)));
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private record Reply(int status, JsonNode body) {
    }

    /**
     * Gọi MoMo. Lỗi được ghi qua {@link GatewayCallLogger} (loại lỗi, mã HTTP, tên lớp nguyên nhân; không URL, không
     * thân, không khóa, không chữ ký) rồi ném {@link SalesException} giữ nguyên nhân gốc. Mã HTTP khác 2xx là lỗi.
     */
    private Reply post(String path, Map<String, Object> body, String op, String reference) {
        long start = System.nanoTime();
        HttpResponse<String> res;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint + path)).timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            calls.failed(op, reference, millisSince(start), null, GatewayErrors.INTERRUPTED, e, null, null, false);
            throw gatewayError("Gọi MoMo bị gián đoạn", e);
        } catch (Exception e) {
            calls.failed(op, reference, millisSince(start), null, GatewayErrors.kindOf(e), e, null, null, false);
            throw gatewayError("Không gọi được MoMo sandbox", e);
        }
        int status = res.statusCode();
        if (status < 200 || status >= 300) {
            calls.failed(op, reference, millisSince(start), status, GatewayErrors.kindOfStatus(status), null, null,
                    res.headers().firstValue("Retry-After").orElse(null), false);
            throw gatewayError("Không gọi được MoMo sandbox", null);
        }
        try {
            return new Reply(status, json.readTree(res.body()));
        } catch (JsonProcessingException | RuntimeException e) {
            calls.failed(op, reference, millisSince(start), status, GatewayErrors.PARSE, e, null, null, true);
            throw gatewayError("Không gọi được MoMo sandbox", e);
        }
    }

    private String sign(String raw) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw gatewayError("Không ký được yêu cầu MoMo", e);
        }
    }

    private static SalesException gatewayError(String message, Throwable cause) {
        return cause == null ? new SalesException(ErrorCode.PAYMENT_GATEWAY_ERROR, message)
                : new SalesException(ErrorCode.PAYMENT_GATEWAY_ERROR, message, cause);
    }
}
