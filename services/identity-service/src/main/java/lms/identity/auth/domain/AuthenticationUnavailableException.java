package lms.identity.auth.domain;

/** Không lấy được role/permission từ access-control-service nên không thể phát hành token. */
public class AuthenticationUnavailableException extends RuntimeException {
    public AuthenticationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
