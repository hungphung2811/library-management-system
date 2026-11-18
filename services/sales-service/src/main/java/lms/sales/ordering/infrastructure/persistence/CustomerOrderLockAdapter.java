package lms.sales.ordering.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import lms.sales.ordering.application.CustomerOrderLockPort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code pg_advisory_xact_lock(bigint)} theo băm của (tenant, khách). Native query đã rà: không đụng bảng nào, chỉ lấy
 * khóa ứng dụng, tự nhả khi commit/rollback. Hai khóa khác nhau có thể trùng băm (hiếm) chỉ gây chờ nhau chốc lát,
 * không sai kết quả.
 */
@Repository
class CustomerOrderLockAdapter implements CustomerOrderLockPort {

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(UUID tenantId, UUID customerUserId) {
        long key = 31L * tenantId.hashCode() + customerUserId.getMostSignificantBits()
                ^ customerUserId.getLeastSignificantBits();
        em.createNativeQuery("select cast(pg_advisory_xact_lock(:k) as varchar)").setParameter("k", key).getSingleResult();
    }
}
