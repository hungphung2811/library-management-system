package lms.identity.apiclient.domain;

/**
 * Lỗi nghiệp vụ của context apiclient. Mã {@link Reason} ánh xạ sang HTTP ở tầng web. Application/credential của
 * tenant khác luôn là {@code *_NOT_FOUND} (cùng thân với "không tồn tại": chống IDOR).
 */
public class ApplicationException extends RuntimeException {

    public enum Reason {
        APPLICATION_NOT_FOUND, CREDENTIAL_NOT_FOUND, INVALID_APPLICATION, INVALID_SCOPE,
        /** Scope có trong danh mục nhưng pha này chưa cấp (ghi, customers:*). */
        SCOPE_NOT_GRANTABLE,
        /** Thao tác không áp dụng cho loại Application này (scope/credential cho website, domain cho tích hợp). */
        KIND_MISMATCH,
        APPLICATION_NAME_DUPLICATED, APPLICATION_LIMIT_EXCEEDED, CREDENTIAL_LIMIT_EXCEEDED,
        APPLICATION_NOT_ACTIVE, APPLICATION_NOT_SUSPENDED, CREDENTIAL_NOT_ACTIVE,
        /** Application INTEGRATION chỉ tạo được khi tenant có hợp đồng ACTIVE (Q3). */
        CONTRACT_REQUIRED
    }

    private final Reason reason;

    public ApplicationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
