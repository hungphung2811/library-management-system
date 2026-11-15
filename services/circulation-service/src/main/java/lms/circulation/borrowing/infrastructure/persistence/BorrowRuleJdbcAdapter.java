package lms.circulation.borrowing.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.circulation.borrowing.domain.BorrowRule;
import lms.circulation.borrowing.domain.BorrowRuleRepository;
import lms.circulation.borrowing.domain.ReaderType;
import lms.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SQL native trên borrow_rules (Review Focus 1): luôn lọc/ghi theo tenant hiện tại một cách tường minh, RLS lọc thêm
 * ở DB. Không có tenant: đọc trả rỗng (không truy vấn), ghi ném TenantRequiredException.
 */
public class BorrowRuleJdbcAdapter implements BorrowRuleRepository {

    private final JdbcTemplate jdbc;

    public BorrowRuleJdbcAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<BorrowRule> findAll() {
        Optional<UUID> tenant = TenantContext.current();
        if (tenant.isEmpty()) return List.of();
        return jdbc.query("SELECT reader_type, max_items, loan_days FROM borrow_rules WHERE tenant_id = ? "
                        + "ORDER BY reader_type",
                (rs, i) -> new BorrowRule(ReaderType.valueOf(rs.getString("reader_type")), rs.getInt("max_items"),
                        rs.getInt("loan_days")),
                tenant.get());
    }

    @Override
    public boolean addIfAbsent(BorrowRule rule) {
        UUID tenant = TenantContext.require();
        int inserted = jdbc.update("INSERT INTO borrow_rules (tenant_id, reader_type, max_items, loan_days) "
                        + "VALUES (?, ?, ?, ?) ON CONFLICT (tenant_id, reader_type) DO NOTHING",
                tenant, rule.readerType().name(), rule.maxItems(), rule.loanDays());
        return inserted > 0;
    }
}
