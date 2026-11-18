package lms.sales.ordering.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import lms.sales.ordering.application.PaymentGatewayPort;
import lms.sales.ordering.domain.PaymentIntent;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;

/**
 * Cổng SePay Test mode (ADR-0028), kiểu thăm dò, không webhook. QR là chuỗi VietQR (tài khoản/ngân hàng của tài khoản
 * Test mode lấy từ cấu hình) với nội dung chuyển khoản là mã tham chiếu. Tiền "về" khi người dùng bấm "Mô phỏng giao
 * dịch" ở my.sepay.vn (Test mode) với nội dung chứa mã tham chiếu; {@link #queryPayment} gọi
 * {@code GET {endpoint}/v2/transactions} (Bearer token Test mode, chỉ đọc) lọc tiền vào đúng số tiền và nội dung, rồi
 * kiểm lại phía ta: nội dung chứa mã (không phân biệt hoa thường), đúng số tiền, thời điểm giao dịch không trước thời điểm
 * tạo lệnh (trừ sai lệch đồng hồ). Token không được ghi log. KHÔNG dùng cho production.
 */
public class SepayPaymentGateway implements PaymentGatewayPort {

    static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    /** SePay trả {@code transaction_date} dạng "2025-02-20 14:15:00" theo giờ Việt Nam. */
    static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** Dung sai đồng hồ giữa máy này và SePay khi so với thời điểm tạo lệnh. */
    static final Duration CLOCK_SKEW = Duration.ofSeconds(10);
    static final String ID_PREFIX = "SEPAY-";

    private final ObjectMapper json;
    private final HttpClient http;
    private final String endpoint;
    private final String token;
    private final String bankBin;
    private final String accountNo;
    private final String accountName;
    private final String bankAccountId;

    public SepayPaymentGateway(ObjectMapper json, String endpoint, String token, String bankBin, String accountNo,
                               String accountName, String bankAccountId) {
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.token = token;
        this.bankBin = bankBin;
        this.accountNo = accountNo;
        this.accountName = accountName;
        this.bankAccountId = bankAccountId == null ? "" : bankAccountId.trim();
    }

    @Override
    public String provider() {
        return PaymentIntent.PROVIDER_SEPAY;
    }

    @Override
    public QrCharge createQr(ChargeRequest request) {
        return new QrCharge(VietQrPayload.build(bankBin, accountNo, request.amount().amount(), request.reference(),
                accountName));
    }

    /** Không có số tiền và mốc thời gian thì không dò (tránh khớp nhầm): luôn rỗng. */
    @Override
    public Optional<GatewayPayment> queryPayment(String reference) {
        return Optional.empty();
    }

    @Override
    public Optional<GatewayPayment> queryPayment(String reference, long amount, Instant notBefore) {
        String url = endpoint + "/v2/transactions?transfer_type=in&amount_in_min=" + amount + "&amount_in_max=" + amount
                + "&transaction_content=" + enc(reference) + "&transaction_date_sort=desc&per_page=100"
                + (bankAccountId.isEmpty() ? "" : "&bank_account_id=" + enc(bankAccountId));
        JsonNode data = get(url).path("data");
        if (!data.isArray()) {
            return Optional.empty();
        }
        String needle = reference.toLowerCase(Locale.ROOT);
        Instant floor = notBefore == null ? null : notBefore.minus(CLOCK_SKEW);
        for (JsonNode tx : data) {
            String id = tx.path("id").asText("");
            String transferType = tx.path("transfer_type").asText("in");
            if (id.isBlank() || !"in".equals(transferType) || tx.path("amount_in").asLong(-1) != amount) {
                continue;
            }
            if (!tx.path("transaction_content").asText("").toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            Instant at = parseDate(tx.path("transaction_date").asText(""));
            if (at == null || (floor != null && at.isBefore(floor))) {
                continue;
            }
            return Optional.of(new GatewayPayment(true, ID_PREFIX + id, amount));
        }
        return Optional.empty();
    }

    static Instant parseDate(String text) {
        try {
            return LocalDateTime.parse(text.trim(), DATE_FORMAT).atZone(VIETNAM).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private JsonNode get(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token).header("Accept", "application/json").GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = res.statusCode();
            if (status == 401 || status == 403) {
                throw gatewayError("SePay từ chối token (kiểm tra PAYMENTS_SEPAY_TOKEN của Test mode)");
            }
            if (status == 429) {
                throw gatewayError("SePay giới hạn tần suất gọi, sẽ thử lại");
            }
            if (status < 200 || status >= 300) {
                throw gatewayError("SePay trả lỗi HTTP " + status);
            }
            return json.readTree(res.body());
        } catch (SalesException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw gatewayError("Gọi SePay bị gián đoạn");
        } catch (Exception e) {
            throw gatewayError("Không gọi được SePay sandbox");
        }
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private static SalesException gatewayError(String message) {
        return new SalesException(ErrorCode.PAYMENT_GATEWAY_ERROR, message);
    }
}
