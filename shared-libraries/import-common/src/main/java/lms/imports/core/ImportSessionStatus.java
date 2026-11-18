package lms.imports.core;

public enum ImportSessionStatus {
    VALIDATED,
    COMMITTING,
    /** Không lưu trong Redis: suy ra từ COMMITTING khi heartbeat quá cũ (service đã chết). Confirm lại sẽ tiếp tục. */
    INTERRUPTED,
    COMMITTED,
    COMMIT_FAILED
}
