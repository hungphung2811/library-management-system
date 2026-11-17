package lms.identity.auth.domain;

/** Yêu cầu giữ lại phiên hiện tại nhưng access token không mang mã phiên (token phát trước ADR-0017). */
public class SessionNotIdentifiedException extends RuntimeException {
    public SessionNotIdentifiedException() {
        super("Không xác định được phiên hiện tại, hãy đăng nhập lại");
    }
}
