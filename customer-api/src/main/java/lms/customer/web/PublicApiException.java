package lms.customer.web;

import org.springframework.http.HttpStatus;

/** Lỗi công khai đã được chuẩn hóa (mã + thông điệp do customer-api viết). Không mang stack trace. */
public class PublicApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public PublicApiException(HttpStatus status, String code, String message) {
        super(message, null, false, false);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** 404 chung cho mọi lý do storefront không khả dụng (chống dò tenant, ADR-0011 2.5). */
    public static PublicApiException shopNotAvailable() {
        return new PublicApiException(HttpStatus.NOT_FOUND, "SHOP_NOT_AVAILABLE", "Cửa hàng không khả dụng");
    }

    /** 503 chung, không lộ tên service/host phía sau. */
    public static PublicApiException serviceUnavailable() {
        return new PublicApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                "Dịch vụ tạm thời không khả dụng, vui lòng thử lại sau");
    }
}
