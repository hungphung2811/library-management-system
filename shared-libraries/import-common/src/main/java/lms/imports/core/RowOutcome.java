package lms.imports.core;

import java.util.List;

/** Kết quả ghi một dòng (không chứa giá trị dòng). */
public record RowOutcome(String sheet, int rowNumber, OutcomeStatus status, List<RowIssue> issues) {

    public RowOutcome {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public static RowOutcome created(ImportRow row) {
        return new RowOutcome(row.sheet(), row.rowNumber(), OutcomeStatus.CREATED, List.of());
    }

    public static RowOutcome skipped(ImportRow row, RowIssue issue) {
        return new RowOutcome(row.sheet(), row.rowNumber(), OutcomeStatus.SKIPPED_DUPLICATE, List.of(issue));
    }

    public static RowOutcome failed(ImportRow row, RowIssue issue) {
        return new RowOutcome(row.sheet(), row.rowNumber(), OutcomeStatus.FAILED, List.of(issue));
    }
}
