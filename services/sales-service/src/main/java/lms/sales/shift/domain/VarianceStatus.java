package lms.sales.shift.domain;

/** Trạng thái duyệt chênh lệch tiền mặt khi đóng ca (ADR-0030 D3.5, D3.7). */
public enum VarianceStatus {
    /** Chênh lệch trong ngưỡng, không cần duyệt. */
    NONE,
    /** Chênh lệch vượt ngưỡng, chờ quản lý duyệt. */
    PENDING_APPROVAL,
    /** Quản lý đã duyệt. */
    APPROVED
}
