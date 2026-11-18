package lms.tenant.export;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Đọc bảng theo keyset bằng JDBC (ADR-0031 F1). Mỗi lô là một truy vấn
 * {@code SELECT <cột whitelist> FROM <bảng> WHERE tenant_id = ? [AND (khóa) > (?)] ORDER BY khóa LIMIT ?}:
 * số câu SQL = số dòng / lô, không N+1, bộ nhớ giới hạn bởi kích thước lô. {@code tenant_id} lọc tường minh (lớp 1),
 * RLS ở DB lọc thêm (lớp 2) vì kết nối lấy từ DataSource đã bọc {@code TenantRlsConnectionInitializer} theo
 * {@code TenantContext}. Tên bảng/cột chỉ đến từ {@link ExportTable} (đã kiểm định danh).
 *
 * <p>Bảng không có khóa duy nhất ({@link ExportTable#uniqueKey()} = false): lô dùng {@code >=} con trỏ, bỏ các dòng cuối
 * lô trùng khóa cuối rồi bắt đầu lô sau từ chính khóa đó, nên mỗi nhóm dòng trùng khóa được đọc nguyên vẹn trong cùng một
 * truy vấn (nhóm dài hơn lô thì tăng gấp đôi giới hạn cho tới khi trọn nhóm); không mất dòng, không lặp dòng.
 *
 * <p>Định dạng ô: UUID/chuỗi nguyên văn (chuỗi bắt đầu bằng {@code = + - @ TAB CR} được thêm dấu nháy đơn phía trước,
 * xem {@link #neutralizeFormula}); boolean {@code true/false}; số thực {@code toPlainString}; thời điểm ISO-8601 UTC
 * ({@code 2026-11-19T10:15:30Z}); ngày ISO; mảng và JSON/JSONB ghi thành chuỗi JSON.
 */
public final class JdbcTenantTableReader implements TenantTableReader {

    private final JdbcTemplate jdbc;

    public JdbcTenantTableReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record Row(List<String> cells, Object[] key) { }

    private record Sql(String first, String next) { }

    @Override
    public long read(ExportTable table, UUID tenantId, int batchSize, RowConsumer sink) throws IOException {
        if (batchSize < 1) throw new IllegalArgumentException("batchSize phải >= 1");
        Sql sql = pageSql(table);
        long total = 0;
        Object[] cursor = null;
        int limit = batchSize;
        while (true) {
            List<Row> rows = cursor == null
                    ? query(table, sql.first(), new Object[] {tenantId, limit})
                    : query(table, sql.next(), nextArgs(tenantId, cursor, limit));
            if (rows.size() < limit) {
                total += emit(rows, rows.size(), sink);
                return total;
            }
            Object[] lastKey = rows.get(rows.size() - 1).key();
            if (table.uniqueKey()) {
                total += emit(rows, rows.size(), sink);
                cursor = lastKey;
                continue;
            }
            int cut = rows.size();
            while (cut > 0 && Arrays.equals(rows.get(cut - 1).key(), lastKey)) cut--;
            if (cut == 0) {
                limit = Math.multiplyExact(limit, 2);
                continue;
            }
            total += emit(rows, cut, sink);
            cursor = lastKey;
            limit = batchSize;
        }
    }

    private static long emit(List<Row> rows, int count, RowConsumer sink) throws IOException {
        for (int i = 0; i < count; i++) sink.accept(rows.get(i).cells());
        return count;
    }

    private static Sql pageSql(ExportTable t) {
        String cols = String.join(", ", t.columns());
        String keyCols = String.join(", ", t.keyColumns());
        String op = t.uniqueKey() ? " > " : " >= ";
        String cursorFilter;
        if (t.keyColumns().size() == 1) {
            cursorFilter = " AND " + keyCols + op + "?";
        } else {
            String marks = String.join(", ", t.keyColumns().stream().map(k -> "?").toList());
            cursorFilter = " AND (" + keyCols + ")" + op + "(" + marks + ")";
        }
        String head = "SELECT " + cols + " FROM " + t.name() + " WHERE tenant_id = ?";
        String tail = " ORDER BY " + keyCols + " LIMIT ?";
        return new Sql(head + tail, head + cursorFilter + tail);
    }

    private static Object[] nextArgs(UUID tenantId, Object[] cursor, int limit) {
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.addAll(Arrays.asList(cursor));
        args.add(limit);
        return args.toArray();
    }

    private List<Row> query(ExportTable t, String sql, Object[] args) {
        int n = t.columns().size();
        int[] keyIdx = t.keyColumns().stream().mapToInt(k -> t.columns().indexOf(k) + 1).toArray();
        List<Row> rows = new ArrayList<>();
        jdbc.query(sql, rs -> {
            List<String> cells = new ArrayList<>(n);
            try {
                for (int i = 1; i <= n; i++) cells.add(formatValue(rs.getObject(i)));
                Object[] key = new Object[keyIdx.length];
                for (int k = 0; k < keyIdx.length; k++) key[k] = rs.getObject(keyIdx[k]);
                rows.add(new Row(cells, key));
            } catch (SQLException e) {
                throw new UncategorizedSQLException("export " + t.name(), sql, e);
            }
        }, args);
        return rows;
    }

    static String formatValue(Object v) throws SQLException {
        if (v == null) return null;
        if (v instanceof String s) return neutralizeFormula(s);
        if (v instanceof UUID || v instanceof Boolean || v instanceof Integer || v instanceof Long
                || v instanceof Short) {
            return v.toString();
        }
        if (v instanceof BigDecimal d) return d.toPlainString();
        if (v instanceof Timestamp ts) return ts.toInstant().toString();
        if (v instanceof OffsetDateTime odt) return odt.toInstant().toString();
        if (v instanceof LocalDateTime ldt) return ldt.toInstant(ZoneOffset.UTC).toString();
        if (v instanceof Instant i) return i.toString();
        if (v instanceof java.sql.Date d) return d.toLocalDate().toString();
        if (v instanceof LocalDate d) return d.toString();
        if (v instanceof Time t) return t.toLocalTime().toString();
        if (v instanceof Array a) return jsonArray((Object[]) a.getArray());
        // PGobject (json/jsonb/...) và kiểu lạ: toString() của PGobject trả về giá trị văn bản
        return v.toString();
    }

    /**
     * Chống chèn công thức bảng tính (CSV injection), cùng quy ước với exporter sales và CsvWriter của báo cáo: ô VĂN BẢN
     * bắt đầu bằng {@code = + - @}, TAB hoặc CR được thêm dấu nháy đơn phía trước. Chỉ áp cho giá trị kiểu chuỗi của cột
     * (số, ngày, boolean, thời điểm, UUID, mảng/JSON không bị đụng: số âm giữ nguyên {@code -5}).
     */
    static String neutralizeFormula(String s) {
        if (s.isEmpty()) return s;
        char c = s.charAt(0);
        return c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r' ? "'" + s : s;
    }

    static String jsonArray(Object[] items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.length; i++) {
            if (i > 0) sb.append(',');
            Object item = items[i];
            if (item == null) {
                sb.append("null");
            } else if (item instanceof Number || item instanceof Boolean) {
                sb.append(item);
            } else {
                sb.append('"').append(escapeJson(item.toString())).append('"');
            }
        }
        return sb.append(']').toString();
    }

    static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
