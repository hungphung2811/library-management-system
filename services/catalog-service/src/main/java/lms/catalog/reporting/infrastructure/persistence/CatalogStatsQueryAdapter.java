package lms.catalog.reporting.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.catalog.reporting.application.CatalogStatsQuery;
import lms.tenant.TenantContext;
import org.springframework.stereotype.Repository;

/**
 * Đọc thống kê bằng native query (đếm có điều kiện, không có entity cho tổng hợp). Mỗi truy vấn có
 * {@code tenant_id = :tenantId} tường minh (native bỏ qua @TenantId) cộng RLS ở DB, và timeout 2 giây (ADR-0018 2.2).
 * Lớp này nằm trong danh sách đã rà của TenantArchitectureRulesTest.
 */
@Repository
class CatalogStatsQueryAdapter implements CatalogStatsQuery {

    static final int TIMEOUT_MS = 2000;
    private static final String TIMEOUT_HINT = "jakarta.persistence.query.timeout";

    @PersistenceContext
    private EntityManager em;

    @Override
    public long countTitles() {
        UUID tenantId = TenantContext.require();
        Object n = em.createNativeQuery("select count(*) from titles where tenant_id = :tenantId")
                .setParameter("tenantId", tenantId)
                .setHint(TIMEOUT_HINT, TIMEOUT_MS)
                .getSingleResult();
        return ((Number) n).longValue();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Long> countCopiesByStatus() {
        UUID tenantId = TenantContext.require();
        List<Object[]> rows = em.createNativeQuery(
                        "select status, count(*) from book_copies where tenant_id = :tenantId group by status")
                .setParameter("tenantId", tenantId)
                .setHint(TIMEOUT_HINT, TIMEOUT_MS)
                .getResultList();
        Map<String, Long> result = new LinkedHashMap<>();
        for (Object[] row : rows) {
            result.put((String) row[0], ((Number) row[1]).longValue());
        }
        return result;
    }
}
