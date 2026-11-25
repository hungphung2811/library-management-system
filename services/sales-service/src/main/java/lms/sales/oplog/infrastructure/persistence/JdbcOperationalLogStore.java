package lms.sales.oplog.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import lms.sales.oplog.application.OperationalLogStore;
import lms.sales.oplog.domain.OperationalLogEvent;
import lms.sales.shared.application.PageResult;
import lms.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC (bảng {@code integration_log_events}, RLS ENABLE+FORCE, như SalesSettingsPersistenceAdapter). Đọc đi theo tenant
 * của request (platform ở phạm vi ALL đọc mọi dòng). Ghi/dọn chạy ở luồng nền không có request nên tự chọn phạm vi:
 * dòng có tenant ghi trong {@code TenantContext} của tenant đó (qua chính sách tenant_isolation), dòng không có tenant và
 * việc dọn chạy ở phạm vi ALL (chính sách platform_insert_untenanted / platform_purge).
 */
@Repository
class JdbcOperationalLogStore implements OperationalLogStore {

    private static final String COLUMNS = "id, tenant_id, occurred_at, channel, provider, event, level, op, outcome, "
            + "http_status, duration_ms, error_kind, error_class, anomaly_type, correlation_id, intent_id, reference";

    private final JdbcTemplate jdbc;

    JdbcOperationalLogStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void saveBatch(List<OperationalLogEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        Map<UUID, List<OperationalLogEvent>> byTenant = new LinkedHashMap<>();
        List<OperationalLogEvent> untenanted = new ArrayList<>();
        for (OperationalLogEvent e : events) {
            if (e.tenantId() == null) {
                untenanted.add(e);
            } else {
                byTenant.computeIfAbsent(e.tenantId(), k -> new ArrayList<>()).add(e);
            }
        }
        for (Map.Entry<UUID, List<OperationalLogEvent>> g : byTenant.entrySet()) {
            TenantContext.callAs(g.getKey(), () -> insert(g.getValue()));
        }
        if (!untenanted.isEmpty()) {
            asPlatform(() -> insert(untenanted));
        }
    }

    private int insert(List<OperationalLogEvent> batch) {
        jdbc.batchUpdate("insert into integration_log_events (" + COLUMNS + ", extra) values "
                        + "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)", batch, batch.size(),
                (ps, e) -> {
                    ps.setObject(1, e.id());
                    ps.setObject(2, e.tenantId());
                    ps.setTimestamp(3, Timestamp.from(e.occurredAt()));
                    ps.setString(4, e.channel());
                    ps.setString(5, e.provider());
                    ps.setString(6, e.event());
                    ps.setString(7, e.level());
                    ps.setString(8, e.op());
                    ps.setString(9, e.outcome());
                    ps.setObject(10, e.httpStatus());
                    ps.setObject(11, e.durationMs());
                    ps.setString(12, e.errorKind());
                    ps.setString(13, e.errorClass());
                    ps.setString(14, e.anomalyType());
                    ps.setString(15, e.correlationId());
                    ps.setObject(16, e.intentId());
                    ps.setString(17, e.reference());
                    ps.setString(18, e.extra().isEmpty() ? null : toJson(e.extra()));
                });
        return batch.size();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<OperationalLogEvent> search(Criteria c, int page, int size) {
        StringBuilder where = new StringBuilder(" where occurred_at >= ? and occurred_at < ?");
        List<Object> args = new ArrayList<>();
        args.add(Timestamp.from(c.from()));
        args.add(Timestamp.from(c.to()));
        where.append(" and channel in (").append("?, ".repeat(c.channels().size() - 1)).append("?)");
        args.addAll(c.channels());
        if (c.tenantId() != null) {
            where.append(" and tenant_id = ?");
            args.add(c.tenantId());
        }
        if (c.provider() != null) {
            where.append(" and provider = ?");
            args.add(c.provider());
        }
        if (c.outcome() != null) {
            where.append(" and outcome = ?");
            args.add(c.outcome());
        }
        if (c.level() != null) {
            where.append(" and level = ?");
            args.add(c.level());
        }
        if (c.anomalyOnly()) {
            where.append(" and anomaly_type is not null");
        }
        Long total = jdbc.queryForObject("select count(*) from integration_log_events" + where, Long.class,
                args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<OperationalLogEvent> items = jdbc.query("select " + COLUMNS + " from integration_log_events" + where
                + " order by occurred_at desc, id desc limit ? offset ?", (rs, i) -> new OperationalLogEvent(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getTimestamp("occurred_at").toInstant(), rs.getString("channel"), rs.getString("provider"),
                rs.getString("event"), rs.getString("level"), rs.getString("op"), rs.getString("outcome"),
                rs.getObject("http_status", Integer.class), rs.getObject("duration_ms", Long.class),
                rs.getString("error_kind"), rs.getString("error_class"), rs.getString("anomaly_type"),
                rs.getString("correlation_id"), rs.getObject("intent_id", UUID.class), rs.getString("reference"),
                Map.of()), pageArgs.toArray());
        return new PageResult<>(items, total == null ? 0 : total, page, size);
    }

    @Override
    public int purgeOlderThan(Instant cutoff, int limit) {
        return asPlatform(() -> jdbc.update("delete from integration_log_events where id in ("
                + "select id from integration_log_events where occurred_at < ? limit ?)", Timestamp.from(cutoff),
                limit));
    }

    /** Chạy ở phạm vi ALL (chỉ luồng nền của chính service này gọi), rồi trả lại phạm vi cũ. */
    private static <T> T asPlatform(Supplier<T> action) {
        UUID previous = TenantContext.current().orElse(null);
        boolean previousAll = TenantContext.isAllTenants();
        TenantContext.bindAllTenants();
        try {
            return action.get();
        } finally {
            if (previousAll) {
                TenantContext.bindAllTenants();
            } else {
                TenantContext.set(previous);
            }
        }
    }

    /** Giá trị đã qua danh sách trắng ký tự ở bộ phân tích; vẫn thoát ký tự để JSON luôn hợp lệ. */
    static String toJson(Map<String, String> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escape(e.getKey())).append("\":\"").append(escape(e.getValue())).append('"');
        }
        return sb.append('}').toString();
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            if (ch == '"' || ch == '\\') {
                sb.append('\\').append(ch);
            } else if (ch < 0x20) {
                sb.append(' ');
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }
}
