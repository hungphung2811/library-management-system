package lms.identity.tenant.domain;

/** Origin sai cú pháp/quy tắc (400 ORIGIN_INVALID). {@code reason} là mã lý do để FE admin hiện thông điệp. */
public class InvalidStorefrontOriginException extends RuntimeException {

    public enum Reason { SYNTAX, SCHEME, PATH, WILDCARD, NULL, HOST, PORT, HTTP_NOT_ALLOWED }

    private final Reason reason;

    public InvalidStorefrontOriginException(Reason reason) {
        super("Origin không hợp lệ (" + reason + "): chỉ nhận dạng scheme://host[:port], không path, query, "
                + "fragment hay ký tự đại diện");
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
