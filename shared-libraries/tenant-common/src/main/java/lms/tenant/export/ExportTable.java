package lms.tenant.export;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Khai báo cố định một bảng được xuất (ADR-0031 D2): tên bảng, danh sách cột (whitelist, không {@code SELECT *}),
 * cột khóa dùng cho phân trang keyset, và các cột bị loại có chủ đích (chỉ để ghi vào {@code _meta.json}).
 *
 * <p>Cột {@code tenant_id} không nằm trong {@code columns}: truy vấn luôn lọc {@code tenant_id = ?} tường minh (cộng
 * RLS ở DB) và CSV chỉ chứa dữ liệu của một tenant nên không cần cột này. Tên bảng/cột phải khớp {@code [a-z][a-z0-9_]*}
 * vì được ghép thẳng vào SQL (chỉ do mã khai báo, không bao giờ do request). Cột có tên gợi ý bí mật (password, token,
 * secret, hash, ...) bị từ chối ngay khi dựng, để không ai thêm nhầm.
 *
 * <p>{@code keyColumns} phải nằm trong {@code columns}. {@code uniqueKey = true} nghĩa là khóa duy nhất trong phạm vi
 * tenant (phân trang {@code > khóa cuối}); {@code false} (bảng không có khóa duy nhất, ví dụ
 * {@code borrowing_items}) thì đọc theo nhóm khóa nguyên vẹn để không mất dòng trùng khóa ({@link #byBusinessKey}).
 * {@code splitEveryRows > 0} chia bảng lớn thành nhiều file {@code <bảng>_001.csv}, {@code _002}, ...
 */
public record ExportTable(String name, List<String> columns, List<String> keyColumns,
                          List<ExportExclusion> excludedColumns, long splitEveryRows, boolean uniqueKey) {

    private static final Pattern IDENT = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i).*(password|passwd|secret|token|hash|salt|credential|api_key|private_key|signature|qr_payload).*");

    public ExportTable {
        requireIdent(name, "tên bảng");
        columns = List.copyOf(columns);
        keyColumns = List.copyOf(keyColumns);
        excludedColumns = List.copyOf(excludedColumns);
        if (columns.isEmpty()) throw new IllegalArgumentException(name + ": cần ít nhất một cột");
        if (columns.stream().distinct().count() != columns.size()) {
            throw new IllegalArgumentException(name + ": trùng tên cột");
        }
        for (String c : columns) {
            requireIdent(c, name + ": tên cột");
            if (c.equals("tenant_id")) throw new IllegalArgumentException(name + ": không xuất cột tenant_id");
            if (SENSITIVE.matcher(c).matches()) {
                throw new IllegalArgumentException(name + "." + c + ": cột có tên nhạy cảm không được xuất");
            }
        }
        if (keyColumns.isEmpty()) throw new IllegalArgumentException(name + ": cần cột khóa");
        if (!columns.containsAll(keyColumns)) {
            throw new IllegalArgumentException(name + ": cột khóa phải nằm trong danh sách cột");
        }
        if (splitEveryRows < 0) throw new IllegalArgumentException(name + ": splitEveryRows không âm");
    }

    /** Bảng có khóa duy nhất {@code keys} (đã nằm trong {@code columns}). */
    public static ExportTable of(String name, List<String> keys, String... columns) {
        return new ExportTable(name, List.of(columns), keys, List.of(), 0, true);
    }

    /** Bảng không có khóa duy nhất: {@code keys} có thể trùng giữa các dòng, đọc theo nhóm khóa nguyên vẹn. */
    public static ExportTable byBusinessKey(String name, List<String> keys, String... columns) {
        return new ExportTable(name, List.of(columns), keys, List.of(), 0, false);
    }

    public ExportTable excluding(String column, String reason) {
        List<ExportExclusion> all = new ArrayList<>(excludedColumns);
        all.add(new ExportExclusion(name + "." + column, reason));
        return new ExportTable(name, columns, keyColumns, all, splitEveryRows, uniqueKey);
    }

    public ExportTable splitEvery(long rows) {
        return new ExportTable(name, columns, keyColumns, excludedColumns, rows, uniqueKey);
    }

    private static void requireIdent(String value, String what) {
        if (value == null || !IDENT.matcher(value).matches()) {
            throw new IllegalArgumentException(what + " không hợp lệ: " + value);
        }
    }
}
