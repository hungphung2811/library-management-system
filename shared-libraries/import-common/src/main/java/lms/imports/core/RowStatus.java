package lms.imports.core;

/** Trạng thái của một dòng ở preview. */
public enum RowStatus {
    VALID,
    INVALID,
    /** Khóa đã có trong DB của tenant: bỏ qua khi confirm. */
    DUPLICATE
}
