package lms.imports.core;

import java.util.List;

/** Lỗi nghiệp vụ của luồng nhập; {@code details} không được chứa dữ liệu dòng. */
public class ImportException extends RuntimeException {

    private final ImportErrorCode errorCode;
    private final List<String> details;

    public ImportException(ImportErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), List.of(), null);
    }

    public ImportException(ImportErrorCode errorCode, String message) {
        this(errorCode, message, List.of(), null);
    }

    public ImportException(ImportErrorCode errorCode, String message, List<String> details) {
        this(errorCode, message, details, null);
    }

    public ImportException(ImportErrorCode errorCode, String message, List<String> details, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public ImportErrorCode errorCode() {
        return errorCode;
    }

    public List<String> details() {
        return details;
    }
}
