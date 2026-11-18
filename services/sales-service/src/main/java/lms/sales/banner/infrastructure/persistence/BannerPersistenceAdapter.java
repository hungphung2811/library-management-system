package lms.sales.banner.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.sales.banner.application.BannerRepository;
import lms.sales.banner.domain.Banner;
import lms.sales.shared.infrastructure.persistence.PersistenceErrors;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Adapter JPA của banner. Truy vấn nào cũng có tenantId tường minh (RLS vẫn chặn ở DB); không native query. */
@Repository
class BannerPersistenceAdapter implements BannerRepository {

    private static final String ORDER = " order by b.sortOrder, b.createdAt, b.id";

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional
    public Banner insert(Banner banner) {
        StorefrontBannerJpaEntity entity = new StorefrontBannerJpaEntity(banner);
        em.persist(entity);
        em.flush();
        return entity.toDomain();
    }

    @Override
    @Transactional
    public Banner update(Banner banner) {
        try {
            StorefrontBannerJpaEntity entity = em.find(StorefrontBannerJpaEntity.class, banner.id());
            if (entity == null) {
                throw Banner.notFound();
            }
            if (entity.getVersion() != banner.version()) {
                throw Banner.versionConflict();
            }
            entity.apply(banner);
            em.flush();
            em.refresh(entity);
            return entity.toDomain();
        } catch (PersistenceException e) {
            if (PersistenceErrors.isOptimisticLock(e)) {
                throw Banner.versionConflict();
            }
            throw e;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Banner> findById(UUID tenantId, UUID id) {
        return em.createQuery("select b from StorefrontBannerJpaEntity b where b.tenantId = :t and b.id = :id",
                        StorefrontBannerJpaEntity.class)
                .setParameter("t", tenantId).setParameter("id", id).getResultList().stream().findFirst()
                .map(StorefrontBannerJpaEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Banner> findAll(UUID tenantId) {
        return em.createQuery("select b from StorefrontBannerJpaEntity b where b.tenantId = :t" + ORDER,
                        StorefrontBannerJpaEntity.class)
                .setParameter("t", tenantId).getResultList().stream().map(StorefrontBannerJpaEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long count(UUID tenantId) {
        return em.createQuery("select count(b) from StorefrontBannerJpaEntity b where b.tenantId = :t", Long.class)
                .setParameter("t", tenantId).getSingleResult();
    }

    @Override
    @Transactional
    public boolean delete(UUID tenantId, UUID id) {
        return em.createQuery("delete from StorefrontBannerJpaEntity b where b.tenantId = :t and b.id = :id")
                .setParameter("t", tenantId).setParameter("id", id).executeUpdate() > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Banner> findLive(UUID tenantId, Instant now, int limit) {
        return em.createQuery("select b from StorefrontBannerJpaEntity b where b.tenantId = :t and b.active = true "
                                + "and (b.startsAt is null or b.startsAt <= :now) "
                                + "and (b.endsAt is null or :now < b.endsAt)" + ORDER,
                        StorefrontBannerJpaEntity.class)
                .setParameter("t", tenantId).setParameter("now", now).setMaxResults(limit).getResultList().stream()
                .map(StorefrontBannerJpaEntity::toDomain).toList();
    }
}
