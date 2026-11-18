package lms.imports.core;

/** Kết quả ghi của một dòng khi confirm. */
public enum OutcomeStatus {
    CREATED,
    /** Khi kiểm lại lúc ghi, khóa đã có (người khác tạo trong lúc chờ, hoặc chunk đã ghi trước khi bị ngắt). */
    SKIPPED_DUPLICATE,
    FAILED
}
