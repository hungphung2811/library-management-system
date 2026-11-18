package lms.customer.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import lms.common.kernel.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.MethodArgumentConversionNotSupportedException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Chuẩn hóa mọi lỗi công khai (ADR-0011 2.9): chỉ chuyển các mã nghiệp vụ trong danh sách cho phép, thông điệp do
 * customer-api viết (không chép thông điệp nội bộ, vì FORBIDDEN của interceptor chứa tên khóa), bỏ details; lỗi phía sau
 * thành 503 chung. Không log thân request (có thể chứa email/điện thoại) và không trả stack trace.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PublicExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(PublicExceptionHandler.class);

    /** Mã nghiệp vụ phía sau được phép chuyển tiếp (giữ nguyên status, mã), kèm thông điệp do customer-api viết. */
    static final Map<String, String> FORWARDED_CODES = Map.ofEntries(
            Map.entry("INSUFFICIENT_STOCK", "Không đủ hàng trong kho"),
            Map.entry("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng"),
            Map.entry("ORDER_STATE_CONFLICT", "Trạng thái đơn hàng không cho phép thao tác này"),
            Map.entry("CLIENT_REQUEST_CONFLICT", "Mã yêu cầu đã được dùng cho một đơn hàng khác"),
            Map.entry("TITLE_NOT_AVAILABLE", "Sách không còn được bán"),
            Map.entry("BOOK_NOT_FOUND", "Không tìm thấy sách"),
            Map.entry("SIGNUP_NOT_AVAILABLE", "Cửa hàng này không mở đăng ký tài khoản"),
            Map.entry("DUPLICATE_USERNAME", "Tên đăng nhập đã được sử dụng"),
            Map.entry("VALIDATION_FAILED", "Dữ liệu không hợp lệ"),
            Map.entry("CONSENT_REQUIRED", "Cần đồng ý xử lý dữ liệu để gửi biểu mẫu"),
            Map.entry("SHOP_CONTACT_LIMIT_EXCEEDED", "Bạn đã gửi quá nhiều yêu cầu, vui lòng thử lại sau"),
            Map.entry("CMS_PAGE_NOT_FOUND", "Không tìm thấy trang"),
            // Chỉ còn do refresh (đã có refresh token hợp lệ); login đã gộp thành LOGIN_FAILED ở HttpAuthClient.
            Map.entry("ACCOUNT_LOCKED", "Tài khoản tạm thời bị khóa"),
            Map.entry("ACCOUNT_DISABLED", "Tài khoản đã bị vô hiệu hóa"),
            Map.entry("UNAUTHORIZED", "Phiên đăng nhập không hợp lệ hoặc đã hết hạn"),
            // ADR-0017: token vừa được làm mới ở nơi khác (tab khác); FE đọc lại token đã lưu rồi thử lại một lần.
            Map.entry("REFRESH_TOKEN_SUPERSEDED", "Phiên vừa được làm mới ở nơi khác, vui lòng thử lại"),
            // Cổng độc giả (ADR-0023 2.5): đặt trước/phiếu mượn của người khác cũng là 404 cùng thân.
            Map.entry("BORROWING_NOT_FOUND", "Không tìm thấy phiếu mượn"),
            Map.entry("RESERVATION_NOT_FOUND", "Không tìm thấy đặt trước"),
            Map.entry("INVALID_RESERVATION", "Đặt trước không thể hủy ở trạng thái hiện tại"),
            Map.entry("READER_PROFILE_NOT_FOUND", "Bạn chưa có thẻ thư viện"));

    /**
     * Mã 400 của service phía sau có tên khác mã công khai: system-service trả VALIDATION_ERROR (body/JSON sai) và
     * CONTACT_REQUEST_INVALID (email sai định dạng...). Cả hai thành VALIDATION_FAILED, không kèm details/thông điệp thô.
     */
    static final Map<String, String> CODE_ALIASES = Map.of(
            "VALIDATION_ERROR", "VALIDATION_FAILED",
            "CONTACT_REQUEST_INVALID", "VALIDATION_FAILED",
            // sales: dữ liệu đơn sai (gồm clientRequestId không phải UUID) và mặt hàng không có/chưa đưa vào bán.
            "INVALID_REQUEST", "VALIDATION_FAILED",
            "STOCK_ITEM_NOT_FOUND", "BOOK_NOT_FOUND",
            "STOCK_ITEM_NOT_REGISTERED", "TITLE_NOT_AVAILABLE",
            // identity trả READER_NOT_FOUND khi người dùng chưa có thẻ; đổi tên để FE phân biệt với lỗi tài khoản.
            "READER_NOT_FOUND", "READER_PROFILE_NOT_FOUND");

    /**
     * Mã được phép kèm {@code details}, và chỉ các mục {@code titleId=<uuid>} (id đầu sách vốn công khai). Mọi mục khác
     * của sales (đặc biệt {@code available=<số tồn>}) bị bỏ để không lộ tồn kho.
     */
    static final java.util.Set<String> CODES_WITH_SAFE_DETAILS =
            java.util.Set.of("INSUFFICIENT_STOCK", "TITLE_NOT_AVAILABLE");
    private static final java.util.regex.Pattern SAFE_DETAIL =
            java.util.regex.Pattern.compile("^titleId=[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final ObjectMapper mapper;

    public PublicExceptionHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @ExceptionHandler(PublicApiException.class)
    ResponseEntity<ErrorResponse> handlePublic(PublicApiException ex) {
        return build(ex.status(), ex.code(), ex.getMessage(), List.of());
    }

    @ExceptionHandler(RestClientResponseException.class)
    ResponseEntity<ErrorResponse> handleDownstream(RestClientResponseException ex) {
        HttpStatusCode status = ex.getStatusCode();
        if (status.is5xxServerError()) {
            log.warn("Service phía sau trả lỗi {}", status.value());
            return unavailable();
        }
        String code = codeOf(ex);
        if ("MODULE_NOT_ENABLED".equals(code) || "TENANT_REQUIRED".equals(code) || "SHOP_NOT_AVAILABLE".equals(code)) {
            PublicApiException na = PublicApiException.shopNotAvailable();
            return build(na.status(), na.code(), na.getMessage(), List.of());
        }
        if ("FORBIDDEN".equals(code)) {
            return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "Bạn không có quyền thực hiện thao tác này", List.of());
        }
        if (code != null && CODE_ALIASES.containsKey(code)) {
            code = CODE_ALIASES.get(code);
        }
        String message = code == null ? null : FORWARDED_CODES.get(code);
        if (message != null) {
            // Mã 4xx lạ (ví dụ 418) vẫn được giữ nguyên; ngoài 4xx thì không tin, trả 400.
            HttpStatusCode safe = status.is4xxClientError() ? status : HttpStatus.BAD_REQUEST;
            return build(safe, code, message,
                    CODES_WITH_SAFE_DETAILS.contains(code) ? safeDetails(ex) : List.of());
        }
        log.debug("Mã lỗi phía sau không nằm trong danh sách cho phép, status={}", status.value());
        return build(HttpStatus.BAD_REQUEST, "REQUEST_REJECTED", "Yêu cầu không được chấp nhận", List.of());
    }

    @ExceptionHandler(RestClientException.class)
    ResponseEntity<ErrorResponse> handleUnreachable(RestClientException ex) {
        log.warn("Không gọi được service phía sau: {}", ex.getClass().getSimpleName());
        return unavailable();
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        // Chỉ tên trường + thông điệp ràng buộc, không bao giờ kèm giá trị bị từ chối.
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage()).toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Dữ liệu không hợp lệ", details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Dữ liệu không hợp lệ", List.of());
    }

    /**
     * Lớp cha của lỗi chuyển đổi thân (khóa JSON trùng, kiểu lạ, số quá lớn...): dữ liệu người dùng sai thì 400 gọn, không
     * log ERROR. Riêng lỗi ghi phản hồi (HttpMessageNotWritableException) là lỗi phía server nên vẫn là 500.
     */
    @ExceptionHandler(HttpMessageConversionException.class)
    ResponseEntity<ErrorResponse> handleConversion(HttpMessageConversionException ex) {
        if (ex instanceof HttpMessageNotWritableException) {
            return handleOther(ex);
        }
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Dữ liệu không hợp lệ", List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> handleMethod(HttpRequestMethodNotSupportedException ex) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Phương thức không được hỗ trợ", List.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException ex) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Định dạng dữ liệu không được hỗ trợ",
                List.of());
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<ErrorResponse> handleNotAcceptable(HttpMediaTypeNotAcceptableException ex) {
        return build(HttpStatus.NOT_ACCEPTABLE, "NOT_ACCEPTABLE", "Định dạng phản hồi không được hỗ trợ", List.of());
    }

    /** Lỗi 4xx còn lại của Spring MVC (thiếu/sai kiểu tham số, thiếu part, ràng buộc tham số...): 400 gọn, không lộ chi tiết. */
    @ExceptionHandler({ServletRequestBindingException.class, TypeMismatchException.class,
            MissingServletRequestPartException.class, HandlerMethodValidationException.class,
            MethodArgumentConversionNotSupportedException.class, BindException.class})
    ResponseEntity<ErrorResponse> handleBadRequest(Exception ex) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Dữ liệu không hợp lệ", List.of());
    }

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    ResponseEntity<ErrorResponse> handleTimeout(AsyncRequestTimeoutException ex) {
        return unavailable();
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "Không tìm thấy tài nguyên", List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleOther(Exception ex) {
        // Chỉ tên lớp ngoại lệ (correlation id đã có trong mẫu log): thông điệp và stack trace của lỗi bất ngờ có thể chứa
        // dữ liệu khách (giá trị bị ánh xạ lỗi, email...), nên không bao giờ log.
        log.error("Lỗi không lường trước: {}", ex.getClass().getName());
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Đã xảy ra lỗi, vui lòng thử lại sau", List.of());
    }

    private static ResponseEntity<ErrorResponse> unavailable() {
        PublicApiException ex = PublicApiException.serviceUnavailable();
        return build(ex.status(), ex.code(), ex.getMessage(), List.of());
    }

    /** Chỉ giữ mục {@code titleId=<uuid>} trong details của service phía sau; mọi lỗi đọc thân cho danh sách rỗng. */
    private List<String> safeDetails(RestClientResponseException ex) {
        try {
            JsonNode details = mapper.readTree(ex.getResponseBodyAsByteArray()).get("details");
            if (details == null || !details.isArray()) {
                return List.of();
            }
            java.util.ArrayList<String> kept = new java.util.ArrayList<>();
            for (JsonNode item : details) {
                if (item.isTextual() && SAFE_DETAIL.matcher(item.asText()).matches()) {
                    kept.add(item.asText());
                }
            }
            return kept;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String codeOf(RestClientResponseException ex) {
        try {
            String body = ex.getResponseBodyAsString();
            if (body == null || body.isBlank()) {
                return null;
            }
            JsonNode code = mapper.readTree(body).get("code");
            return code != null && code.isTextual() ? code.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static ResponseEntity<ErrorResponse> build(HttpStatusCode status, String code, String message, List<String> details) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(ErrorResponse.of(code, message, details));
    }
}
