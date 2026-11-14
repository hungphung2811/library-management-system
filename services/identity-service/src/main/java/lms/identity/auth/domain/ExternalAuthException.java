package lms.identity.auth.domain;

/** Lỗi nghiệp vụ của đăng nhập ngoài; tầng web ánh xạ reason thành mã HTTP. */
public class ExternalAuthException extends RuntimeException {

    public enum Reason {
        PROVIDER_NOT_CONFIGURED, RETURN_URL_NOT_ALLOWED, INVALID_STATE, EXCHANGE_FAILED, PROVIDER_UNAVAILABLE,
        ACCOUNT_NOT_LINKED, ALREADY_LINKED, EXTERNAL_LOGIN_NOT_FOUND, INVALID_TICKET
    }

    private final Reason reason;

    public ExternalAuthException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ExternalAuthException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
