package lms.migration.domain;

/** Trạng thái một script migration, rút gọn từ trạng thái của Flyway. */
public enum MigrationState {
    APPLIED,
    PENDING,
    FAILED,
    /** Đã ghi trong lịch sử nhưng không còn file script tương ứng. */
    MISSING,
    IGNORED,
    OTHER
}
