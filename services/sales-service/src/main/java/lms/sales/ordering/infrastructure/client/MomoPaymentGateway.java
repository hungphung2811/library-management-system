package lms.sales.ordering.infrastructure.client;

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

    public MomoPaymentGateway(ObjectMapper json, String endpoint, String partnerCode, String accessKey,
                              String secretKey, String redirectUrl, String ipnUrl) {
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
        JsonNode res = post("/v2/gateway/api/create", body);
        if (res.path("resultCode").asInt(-1) != 0) {
            throw gatewayError("MoMo từ chối tạo giao dịch: " + res.path("message").asText("không rõ lý do"));
        }
        String qr = res.path("qrCodeUrl").asText("");
        if (qr.isBlank()) {
            qr = res.path("payUrl").asText("");
        }
        if (qr.isBlank()) {
            throw gatewayError("MoMo không trả mã QR");
        }
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
        JsonNode res = post("/v2/gateway/api/query", body);
        if (res.path("resultCode").asInt(-1) != 0) {
            return Optional.empty();
        }
        String txn = res.path("transId").asText("");
        if (txn.isBlank() || "0".equals(txn)) {
            return Optional.empty();
        }
        return Optional.of(new GatewayPayment(true, "MOMO-" + txn, res.path("amount").asLong(-1)));
    }

    private JsonNode post(String path, Map<String, Object> body) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint + path)).timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return json.readTree(res.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw gatewayError("Gọi MoMo bị gián đoạn");
        } catch (Exception e) {
            throw gatewayError("Không gọi được MoMo sandbox");
        }
    }

    private String sign(String raw) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw gatewayError("Không ký được yêu cầu MoMo");
        }
    }

    private static SalesException gatewayError(String message) {
        return new SalesException(ErrorCode.PAYMENT_GATEWAY_ERROR, message);
    }
}
