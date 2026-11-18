package lms.identity.tenant.application;

/**
 * Lỗi quản lý domain allowlist của một Application (SaaS API Authentication, pha P1). Gói các exception của domain
 * tenant để context apiclient chỉ cần dùng tầng application, và KHÔNG lộ tenant chủ khi origin trùng (API này do
 * tenant admin gọi, khác API platform của ADR-0013 có trả tenantId của chủ).
 */
public class ApplicationOriginException extends RuntimeException {

    public enum Reason { INVALID, DUPLICATED, LIMIT_EXCEEDED, NOT_FOUND }

    private final Reason reason;
    private final String detail;

    public ApplicationOriginException(Reason reason, String message, String detail) {
        super(message);
        this.reason = reason;
        this.detail = detail;
    }

    public Reason reason() {
        return reason;
    }

    /** Mã chi tiết (ví dụ lý do origin không hợp lệ), có thể null. */
    public String detail() {
        return detail;
    }
}
