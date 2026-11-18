package lms.sales.tenantdata.infrastructure.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lms.sales.tenantdata.application.TenantTableReader;
import lms.sales.tenantdata.domain.ExportColumn;
import lms.sales.tenantdata.domain.ExportTable;
import lms.sales.tenantdata.domain.ColumnKind;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đọc theo con trỏ bằng JDBC thuần với danh sách cột cố định của {@link ExportTable} (tên bảng/cột đến từ danh mục
 * trong code, đã kiểm {@code [a-z0-9_]}, không có đầu vào người dùng). Luôn lọc {@code tenant_id} tường minh; RLS
 * ({@code app.tenant_id} từ {@code TenantContext} của luồng gọi) là lớp chặn thứ hai. Mỗi lô là một câu SELECT, không
 * N+1.
 */
@Repository
class JdbcTenantTableReader implements TenantTableReader {

    private final JdbcTemplate jdbc;

    JdbcTenantTableReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Object[]> readBatch(UUID tenantId, ExportTable table, Object afterKey, int limit) {
        List<ExportColumn> columns = table.columns();
        String select = String.join(", ", columns.stream().map(ExportColumn::name).toList());
        String key = table.keyColumn();
        StringBuilder sql = new StringBuilder("SELECT ").append(select).append(" FROM ").append(table.name())
                .append(" WHERE tenant_id = ?");
        if (afterKey != null) {
            sql.append(" AND ").append(key).append(" > ?");
        }
        sql.append(" ORDER BY ").append(key).append(" LIMIT ?");
        return jdbc.query(sql.toString(), (PreparedStatement ps) -> {
            int i = 1;
            ps.setObject(i++, tenantId);
            if (afterKey != null) {
                ps.setObject(i++, afterKey);
            }
            ps.setInt(i, limit);
        }, (ResultSet rs, int rowNum) -> mapRow(rs, columns));
    }

    private static Object[] mapRow(ResultSet rs, List<ExportColumn> columns) throws SQLException {
        Object[] row = new Object[columns.size()];
        for (int i = 0; i < row.length; i++) {
            row[i] = read(rs, i + 1, columns.get(i).kind());
        }
        return row;
    }

    private static Object read(ResultSet rs, int index, ColumnKind kind) throws SQLException {
        switch (kind) {
            case UUID:
                return rs.getObject(index, UUID.class);
            case NUMBER: {
                long v = rs.getLong(index);
                return rs.wasNull() ? null : (Long) v;
            }
            case BOOLEAN: {
                boolean v = rs.getBoolean(index);
                return rs.wasNull() ? null : (Boolean) v;
            }
            case TIMESTAMP: {
                OffsetDateTime v = rs.getObject(index, OffsetDateTime.class);
                return v == null ? null : (Instant) v.toInstant();
            }
            case DATE:
                return rs.getObject(index, LocalDate.class);
            case TEXT:
            case JSON:
            default:
                return rs.getString(index);
        }
    }
}
