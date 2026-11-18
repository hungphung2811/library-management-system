package lms.catalog.catalog.application.imports;

import lms.imports.core.RowIssue;

/**
 * Lỗi nghiệp vụ của MỘT dòng (từ domain hoặc đọc giá trị ô); handler bắt lại và đổi thành {@link RowIssue}. Không phải
 * lỗi hệ thống nên không có stack trace.
 */
final class RowProblem extends RuntimeException {

    private final String column;
    private final String code;

    RowProblem(String column, String code, String message) {
        super(message, null, false, false);
        this.column = column;
        this.code = code;
    }

    RowIssue toIssue() {
        return RowIssue.error(column, code, getMessage());
    }
}
