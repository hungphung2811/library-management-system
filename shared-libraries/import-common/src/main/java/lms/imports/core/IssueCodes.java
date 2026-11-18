package lms.imports.core;

/** Mã lỗi dòng do thư viện sinh (kiểm cú pháp chung). Mã nghiệp vụ do từng handler định nghĩa. */
public final class IssueCodes {

    private IssueCodes() {
    }

    public static final String REQUIRED = "REQUIRED";
    public static final String INVALID_NUMBER = "INVALID_NUMBER";
    public static final String INVALID_DATE = "INVALID_DATE";
    public static final String INVALID_ENUM = "INVALID_ENUM";
    public static final String INVALID_BOOLEAN = "INVALID_BOOLEAN";
    public static final String CELL_TOO_LONG = "CELL_TOO_LONG";
    public static final String TOO_LONG = "TOO_LONG";
    /** Dùng chung cho handler: trùng khóa với dòng khác trong cùng file. */
    public static final String DUPLICATE_IN_FILE = "DUPLICATE_IN_FILE";
}
