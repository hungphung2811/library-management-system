package lms.imports.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Một dòng dữ liệu: giá trị THÔ đã chuẩn hóa thành chuỗi (không phải đối tượng đã kiểm), cộng trạng thái preview.
 *
 * @param sheet     tên sheet theo schema
 * @param rowNumber số dòng theo Excel (tính cả dòng tiêu đề, dòng dữ liệu đầu tiên là 2)
 * @param values    key cột -> chuỗi (cột trống không có mặt hoặc là chuỗi rỗng)
 * @param extra     dữ liệu phụ handler muốn giữ trong phiên (ví dụ {@code titleId} đã tra); không hiển thị cho FE
 */
public record ImportRow(String sheet, int rowNumber, Map<String, String> values, RowStatus status, List<RowIssue> issues,
                        Map<String, String> extra) {

    public ImportRow {
        values = values == null ? Map.of() : Map.copyOf(values);
        issues = issues == null ? List.of() : List.copyOf(issues);
        extra = extra == null ? Map.of() : Map.copyOf(extra);
        status = status == null ? RowStatus.VALID : status;
    }

    public static ImportRow of(String sheet, int rowNumber, Map<String, String> values) {
        return new ImportRow(sheet, rowNumber, values, RowStatus.VALID, List.of(), Map.of());
    }

    /** Giá trị của cột, chuỗi rỗng nếu không có. */
    public String value(String key) {
        String v = values.get(key);
        return v == null ? "" : v;
    }

    public boolean hasValue(String key) {
        return !value(key).isEmpty();
    }

    public ImportRow withStatus(RowStatus newStatus) {
        return new ImportRow(sheet, rowNumber, values, newStatus, issues, extra);
    }

    /** Thêm vấn đề; vấn đề mức ERROR chỉ đổi trạng thái sang INVALID nếu dòng đang VALID (DUPLICATE giữ nguyên). */
    public ImportRow withIssue(RowIssue issue) {
        List<RowIssue> merged = new ArrayList<>(issues);
        merged.add(issue);
        RowStatus next = status == RowStatus.VALID && issue.severity() == Severity.ERROR ? RowStatus.INVALID : status;
        return new ImportRow(sheet, rowNumber, values, next, merged, extra);
    }

    /** Đánh dấu INVALID kèm vấn đề (ghi đè DUPLICATE). */
    public ImportRow invalid(RowIssue issue) {
        List<RowIssue> merged = new ArrayList<>(issues);
        merged.add(issue);
        return new ImportRow(sheet, rowNumber, values, RowStatus.INVALID, merged, extra);
    }

    /** Đánh dấu DUPLICATE (khóa đã có trong DB), kèm vấn đề giải thích. */
    public ImportRow duplicate(RowIssue issue) {
        List<RowIssue> merged = new ArrayList<>(issues);
        merged.add(issue);
        return new ImportRow(sheet, rowNumber, values, RowStatus.DUPLICATE, merged, extra);
    }

    public ImportRow withExtra(String key, String value) {
        Map<String, String> merged = new LinkedHashMap<>(extra);
        merged.put(key, value);
        return new ImportRow(sheet, rowNumber, values, status, issues, merged);
    }
}
