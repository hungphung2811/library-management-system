package lms.imports.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** DTO trả cho FE (JSON). Không chứa mật khẩu, {@code extra} của dòng hay dữ liệu của phiên khác. */
public final class ImportViews {

    private ImportViews() {
    }

    public record ColumnView(String key, String label, boolean required) {
    }

    public record SheetView(String sheet, List<ColumnView> columns, int total, int valid, int invalid, int duplicate,
                            int warning) {
    }

    public record Totals(int total, int valid, int invalid, int duplicate, int warning) {
    }

    public record Progress(int processed, int total) {
    }

    public record Result(int created, int skippedDuplicate, int failed) {
    }

    /** {@code progress} và {@code result} null khi chưa confirm; {@code errorCode} rỗng nếu không lỗi. */
    public record SessionView(UUID importId, String type, ImportSessionStatus status, String fileName, Instant createdAt,
                              Instant expiresAt, List<SheetView> sheets, Totals totals, Progress progress, Result result,
                              String errorCode) {
    }

    public record RowView(String sheet, int rowNumber, RowStatus status, Map<String, String> values,
                          List<RowIssue> issues) {
    }

    public record RowsPage(List<RowView> items, int page, int size, long totalItems, int totalPages) {
    }

    /** Query của {@code GET .../rows}; null thì dùng mặc định (page 0, size 50). */
    public record RowsQuery(RowStatus status, String sheet, Integer page, Integer size) {
    }
}
