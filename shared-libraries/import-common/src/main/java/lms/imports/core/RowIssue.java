package lms.imports.core;

/**
 * Một vấn đề của dòng. Thông điệp không được chứa dữ liệu nhạy cảm của dòng khác (ví dụ không nêu tenant nào đã có
 * username).
 *
 * @param column key cột (null nếu lỗi cả dòng)
 */
public record RowIssue(String column, Severity severity, String code, String message) {

    public static RowIssue error(String column, String code, String message) {
        return new RowIssue(column, Severity.ERROR, code, message);
    }

    public static RowIssue warning(String column, String code, String message) {
        return new RowIssue(column, Severity.WARNING, code, message);
    }
}
