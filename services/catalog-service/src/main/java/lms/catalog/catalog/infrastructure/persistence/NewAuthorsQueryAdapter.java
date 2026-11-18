package lms.catalog.catalog.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.catalog.catalog.application.NewAuthorsQuery;
import lms.tenant.TenantContext;
import org.springframework.stereotype.Repository;

/**
 * Một câu native (DISTINCT ON) vì entity không map created_at. Có {@code tenant_id = :tenantId} tường minh (native bỏ
 * qua @TenantId) cộng RLS ở DB. Lớp này nằm trong danh sách đã rà của TenantArchitectureRulesTest. Index
 * idx_titles_tenant_created_at (V11).
 */
@Repository
class NewAuthorsQueryAdapter implements NewAuthorsQuery {

    private static final String SQL = "select x.name from ("
            + " select distinct on (lower(btrim(author))) btrim(author) as name, created_at"
            + " from titles where tenant_id = :tenantId and created_at >= :since and btrim(author) <> ''"
            + " order by lower(btrim(author)), created_at desc, id desc) x"
            + " order by x.created_at desc, x.name limit :limit";

    @PersistenceContext
    private EntityManager em;

    @Override
    @SuppressWarnings("unchecked")
    public List<String> findAuthorsWithTitlesSince(Instant since, int limit) {
        UUID tenantId = TenantContext.require();
        return em.createNativeQuery(SQL)
                .setParameter("tenantId", tenantId)
                .setParameter("since", since)
                .setParameter("limit", limit)
                .setHint("jakarta.persistence.query.timeout", 2000)
                .getResultList();
    }
}
