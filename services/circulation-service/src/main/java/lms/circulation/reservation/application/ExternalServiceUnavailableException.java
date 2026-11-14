package lms.circulation.reservation.application;

/** Service phụ thuộc (catalog, identity) không trả lời được; ánh xạ thành 503 ở tầng web. */
public class ExternalServiceUnavailableException extends RuntimeException {
    public ExternalServiceUnavailableException(String service, Throwable cause) {
        super("Không gọi được " + service + ", vui lòng thử lại sau", cause);
    }

    public ExternalServiceUnavailableException(String message) {
        super(message);
    }
}
