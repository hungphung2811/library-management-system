package lms.identity.tenantdata.domain;

/** Trạng thái yêu cầu xuất dữ liệu tenant (ADR-0031 D3). */
public enum ExportStatus {
    PENDING, RUNNING, READY, FAILED, EXPIRED;

    /** Đang chiếm chỗ của tenant (chỉ mục duy nhất một phần). */
    public boolean inProgress() {
        return this == PENDING || this == RUNNING;
    }
}
