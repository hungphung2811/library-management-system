package lms.identity.tenantdata.domain;

/** Lỗi nghiệp vụ của xuất dữ liệu tenant; {@link Reason} là mã lỗi trả về FE (ADR-0031 mục 3.2). */
public class TenantDataExportException extends RuntimeException {

    public enum Reason {
        /** Không tìm thấy hoặc không thuộc tenant của người gọi (cùng một thân, chống dò IDOR). */
        EXPORT_NOT_FOUND,
        EXPORT_IN_PROGRESS,
        EXPORT_RATE_LIMITED,
        EXPORT_EXPIRED,
        /** Gói chưa sẵn sàng để tải (PENDING, RUNNING hoặc FAILED). */
        EXPORT_NOT_READY,
        EXPORT_NOT_CONFIGURED,
        TENANT_NOT_FOUND
    }

    private final Reason reason;

    public TenantDataExportException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
