package lms.sales.shared.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Map;
import java.util.UUID;
import lms.sales.shared.application.DocumentNumberPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Số chứng từ theo tenant bằng bộ đếm trong cùng transaction tạo chứng từ: JPQL UPDATE (khóa dòng đếm đến hết
 * transaction, nên hai đơn cùng tenant tuần tự hóa ở bước này, chấp nhận ở quy mô một cửa hàng) rồi SELECT.
 * Lần đầu của một (tenant, tên) chưa có dòng: chèn bằng một câu {@code INSERT ... ON CONFLICT DO NOTHING}
 * (JDBC cùng transaction/kết nối, vẫn chịu RLS), vì JPA không có "chèn nếu chưa có" mà không làm hỏng transaction.
 */
@Repository
class DocumentNumberAdapter implements DocumentNumberPort {

    private static final Map<String, String> PREFIX = Map.of(ORDER, "DH", RECEIPT, "PN", RETURN, "TH");

    @PersistenceContext
    private EntityManager em;

    private final JdbcTemplate jdbc;

    DocumentNumberAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public String next(UUID tenantId, String sequence) {
        int updated = bump(tenantId, sequence);
        if (updated == 0) {
            jdbc.update("INSERT INTO sales_counters (tenant_id, name, last_value) VALUES (?, ?, 0) "
                    + "ON CONFLICT (tenant_id, name) DO NOTHING", tenantId, sequence);
            updated = bump(tenantId, sequence);
            if (updated != 1) {
                throw new IllegalStateException("Không tăng được bộ đếm " + sequence);
            }
        }
        Long value = em.createQuery("select c.lastValue from SalesCounterJpaEntity c "
                        + "where c.tenantId = :t and c.name = :n", Long.class)
                .setParameter("t", tenantId).setParameter("n", sequence).getSingleResult();
        return PREFIX.getOrDefault(sequence, sequence.substring(0, Math.min(3, sequence.length())))
                + "-" + String.format("%06d", value);
    }

    private int bump(UUID tenantId, String sequence) {
        return em.createQuery("update SalesCounterJpaEntity c set c.lastValue = c.lastValue + 1 "
                        + "where c.tenantId = :t and c.name = :n")
                .setParameter("t", tenantId).setParameter("n", sequence).executeUpdate();
    }
}
