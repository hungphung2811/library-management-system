package lms.sales.contact.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.sales.contact.application.ShopContactRepository;
import lms.sales.contact.domain.ShopContactRequest;
import lms.sales.shared.application.PageResult;
import lms.sales.shared.infrastructure.persistence.PersistenceErrors;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adapter JPA của yêu cầu liên hệ. Truy vấn nào cũng có {@code tenantId} tường minh (RLS vẫn chặn ở DB). Ẩn danh
 * hàng loạt là JPQL bulk update có điều kiện "chưa ẩn danh" nên chạy lại/chạy chồng chỉ cập nhật 0 dòng; tăng version
 * để khóa lạc quan của thao tác đồng thời không bỏ sót.
 */
@Repository
class ShopContactPersistenceAdapter implements ShopContactRepository {

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional
    public ShopContactRequest insert(ShopContactRequest request) {
        ShopContactRequestJpaEntity entity = new ShopContactRequestJpaEntity(request);
        em.persist(entity);
        em.flush();
        return entity.toDomain();
    }

    @Override
    @Transactional
    public ShopContactRequest update(ShopContactRequest request) {
        try {
            ShopContactRequestJpaEntity entity = em.find(ShopContactRequestJpaEntity.class, request.id());
            if (entity == null) {
                throw ShopContactRequest.notFound();
            }
            if (entity.getVersion() != request.version()) {
                throw ShopContactRequest.stateConflict("Yêu cầu liên hệ đã bị thay đổi bởi thao tác khác");
            }
            entity.apply(request);
            em.flush();
            em.refresh(entity);
            return entity.toDomain();
        } catch (PersistenceException e) {
            if (PersistenceErrors.isOptimisticLock(e)) {
                throw ShopContactRequest.stateConflict("Yêu cầu liên hệ đã bị thay đổi bởi thao tác khác");
            }
            throw e;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ShopContactRequest> findById(UUID tenantId, UUID id) {
        return em.createQuery("select c from ShopContactRequestJpaEntity c where c.tenantId = :t and c.id = :id",
                        ShopContactRequestJpaEntity.class)
                .setParameter("t", tenantId).setParameter("id", id).getResultList().stream().findFirst()
                .map(ShopContactRequestJpaEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<ShopContactRequest> search(UUID tenantId, SearchQuery q) {
        List<String> conditions = new ArrayList<>(List.of("c.tenantId = :t"));
        if (q.status() != null) {
            conditions.add("c.status = :status");
        }
        if (q.topic() != null) {
            conditions.add("c.topic = :topic");
        }
        if (q.from() != null) {
            conditions.add("c.createdAt >= :from");
        }
        if (q.to() != null) {
            conditions.add("c.createdAt < :to");
        }
        String where = " from ShopContactRequestJpaEntity c where " + String.join(" and ", conditions);
        var count = em.createQuery("select count(c)" + where, Long.class);
        var rows = em.createQuery("select c" + where + " order by c.createdAt desc, c.id desc",
                ShopContactRequestJpaEntity.class);
        for (var query : List.of(count, rows)) {
            query.setParameter("t", tenantId);
            if (q.status() != null) {
                query.setParameter("status", q.status().name());
            }
            if (q.topic() != null) {
                query.setParameter("topic", q.topic().name());
            }
            if (q.from() != null) {
                query.setParameter("from", q.from());
            }
            if (q.to() != null) {
                query.setParameter("to", q.to());
            }
        }
        List<ShopContactRequest> items = rows.setFirstResult(q.page() * q.size()).setMaxResults(q.size())
                .getResultList().stream().map(ShopContactRequestJpaEntity::toDomain).toList();
        return new PageResult<>(items, count.getSingleResult(), q.page(), q.size());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShopContactRequest> findBySenderSince(UUID tenantId, UUID customerUserId, Instant since) {
        return em.createQuery("select c from ShopContactRequestJpaEntity c where c.tenantId = :t "
                                + "and c.customerUserId = :u and c.createdAt > :since order by c.createdAt desc",
                        ShopContactRequestJpaEntity.class)
                .setParameter("t", tenantId).setParameter("u", customerUserId).setParameter("since", since)
                .getResultList().stream().map(ShopContactRequestJpaEntity::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> findDueForAnonymization(UUID tenantId, Instant cutoff, int limit) {
        return em.createQuery("select c.id from ShopContactRequestJpaEntity c where c.tenantId = :t "
                        + "and c.piiAnonymizedAt is null and c.createdAt < :cutoff order by c.createdAt, c.id",
                        UUID.class)
                .setParameter("t", tenantId).setParameter("cutoff", cutoff).setMaxResults(limit).getResultList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int anonymizeAll(UUID tenantId, List<UUID> ids, Instant now) {
        if (ids.isEmpty()) {
            return 0;
        }
        return em.createQuery("update ShopContactRequestJpaEntity c set c.customerUserId = null, c.message = null, "
                        + "c.contactName = null, c.contactEmail = null, c.contactPhone = null, "
                        + "c.piiAnonymizedAt = :now, c.version = c.version + 1 "
                        + "where c.tenantId = :t and c.id in :ids and c.piiAnonymizedAt is null")
                .setParameter("now", now).setParameter("t", tenantId).setParameter("ids", ids).executeUpdate();
    }
}
