package lms.sales.ordering.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
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
    private final GatewayCallLogger calls;

    public SepayPaymentGateway(ObjectMapper json, String endpoint, String token, String bankBin, String accountNo,
                               String accountName, String bankAccountId) {
        this(json, endpoint, token, bankBin, accountNo, accountName, bankAccountId,
                GatewayCallLogger.standalone(PaymentIntent.PROVIDER_SEPAY));
    }

    public SepayPaymentGateway(ObjectMapper json, String endpoint, String token, String bankBin, String accountNo,
                               String accountName, String bankAccountId, GatewayCallLogger calls) {
        this.calls = calls;
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
        QrCharge qr = new QrCharge(VietQrPayload.build(bankBin, accountNo, request.amount().amount(),
                request.reference(), accountName));
        calls.created(request.reference(), 0, null);
        return qr;
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
        long start = System.nanoTime();
        Reply reply = get(url, reference);
        JsonNode data = reply.body().path("data");
        if (!data.isArray()) {
            // 2xx nhưng thiếu mảng data: phản hồi lạ (SePay đổi định dạng hoặc trả nội dung không phải sổ giao dịch)
            calls.failed("queryPayment", reference, millisSince(start), reply.status(), GatewayErrors.PARSE, null,
                    null, null, true);
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
            calls.matched(reference, millisSince(start), reply.status());
            return Optional.of(new GatewayPayment(true, ID_PREFIX + id, amount, at));
        }
        calls.noMatch(reference, millisSince(start), reply.status(), null);
        return Optional.empty();
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private record Reply(int status, JsonNode body) {
    }

    static Instant parseDate(String text) {
        try {
            return LocalDateTime.parse(text.trim(), DATE_FORMAT).atZone(VIETNAM).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Gọi SePay. Mọi lỗi được ghi qua {@link GatewayCallLogger} (chỉ loại lỗi, mã HTTP, tên lớp nguyên nhân; không URL,
     * không thân, không getMessage()) rồi ném {@link SalesException} giữ nguyên nhân gốc, thông điệp trả FE như cũ.
     */
    private Reply get(String url, String reference) {
        long start = System.nanoTime();
        HttpResponse<String> res;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token).header("Accept", "application/json").GET().build();
            res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            calls.failed("queryPayment", reference, millisSince(start), null, GatewayErrors.INTERRUPTED, e, null,
                    null, false);
            throw gatewayError("Gọi SePay bị gián đoạn", e);
        } catch (Exception e) {
            calls.failed("queryPayment", reference, millisSince(start), null, GatewayErrors.kindOf(e), e, null, null,
                    false);
            throw gatewayError("Không gọi được SePay sandbox", e);
        }
        int status = res.statusCode();
        if (status < 200 || status >= 300) {
            String kind = GatewayErrors.kindOfStatus(status);
            calls.failed("queryPayment", reference, millisSince(start), status, kind, null,
                    partnerCodeOf(res.body()), res.headers().firstValue("Retry-After").orElse(null), false);
            if (status == 401 || status == 403) {
                throw gatewayError("SePay từ chối token (kiểm tra PAYMENTS_SEPAY_TOKEN của Test mode)", null);
            }
            if (status == 429) {
                throw gatewayError("SePay giới hạn tần suất gọi, sẽ thử lại", null);
            }
            throw gatewayError("SePay trả lỗi HTTP " + status, null);
        }
        try {
            return new Reply(status, json.readTree(res.body()));
        } catch (JsonProcessingException | RuntimeException e) {
            calls.failed("queryPayment", reference, millisSince(start), status, GatewayErrors.PARSE, e, null, null,
                    true);
            throw gatewayError("Không gọi được SePay sandbox", e);
        }
    }

    /** Mã lỗi của SePay trong thân lỗi (error_code) nếu có và đúng dạng an toàn; mọi lỗi đọc đều bị nuốt. */
    private String partnerCodeOf(String body) {
        try {
            return body == null ? null : json.readTree(body).path("error_code").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private static SalesException gatewayError(String message, Throwable cause) {
        return cause == null ? new SalesException(ErrorCode.PAYMENT_GATEWAY_ERROR, message)
                : new SalesException(ErrorCode.PAYMENT_GATEWAY_ERROR, message, cause);
    }
}
