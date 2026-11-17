package lms.api.dashboard.service;

import org.springframework.http.HttpStatus;

/** Lỗi do chính BFF quyết định khi gộp (403 FORBIDDEN, 503 SERVICE_UNAVAILABLE); lỗi của service được ném lại nguyên. */
public class DashboardException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public DashboardException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
