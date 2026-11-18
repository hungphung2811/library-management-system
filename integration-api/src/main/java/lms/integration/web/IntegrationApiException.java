package lms.integration.web;

import org.springframework.http.HttpStatus;

/** Lỗi đã chuẩn hóa (mã + thông điệp do integration-api viết). Không mang stack trace. */
public class IntegrationApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public IntegrationApiException(HttpStatus status, String code, String message) {
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

    /** 503 chung, không lộ tên service/host phía sau. */
    public static IntegrationApiException serviceUnavailable() {
        return new IntegrationApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                "Dịch vụ tạm thời không khả dụng, vui lòng thử lại sau");
    }
}
