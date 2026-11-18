package lms.sales.stock.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.sales.shared.application.PageResult;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.shared.infrastructure.persistence.Like;
import lms.sales.shared.infrastructure.persistence.PersistenceErrors;
import lms.sales.stock.application.SupplierRepository;
import lms.sales.stock.domain.Supplier;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class SupplierPersistenceAdapter implements SupplierRepository {

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional
    public Supplier save(Supplier s) {
        try {
            SupplierJpaEntity entity = em.find(SupplierJpaEntity.class, s.id());
            if (entity == null) {
                entity = new SupplierJpaEntity(s.id(), s.name(), s.phone(), s.email(), s.address(), s.note(),
                        s.active());
                em.persist(entity);
            } else {
                entity.apply(s.name(), s.phone(), s.email(), s.address(), s.note(), s.active());
            }
            em.flush();
            return toDomain(entity);
        } catch (PersistenceException e) {
            if (PersistenceErrors.isUniqueViolation(e, "uq_suppliers_tenant_name")) {
                throw new SalesException(ErrorCode.SUPPLIER_EXISTS, "Nhà cung cấp đã tồn tại");
            }
            throw e;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Set<String> existingLowerNames(UUID tenantId, java.util.Collection<String> lowerNames) {
        java.util.Set<String> out = new java.util.HashSet<>();
        List<String> all = List.copyOf(new java.util.LinkedHashSet<>(lowerNames));
        for (int from = 0; from < all.size(); from += 1_000) {
            out.addAll(em.createQuery("select lower(s.name) from SupplierJpaEntity s where s.tenantId = :t "
                            + "and lower(s.name) in :names", String.class).setParameter("t", tenantId)
                    .setParameter("names", all.subList(from, Math.min(all.size(), from + 1_000))).getResultList());
        }
        return out;
    }

    @Override
    @Transactional
    public void insertAll(List<Supplier> suppliers) {
        if (suppliers.isEmpty()) {
            return;
        }
        try {
            for (Supplier s : suppliers) {
                em.persist(new SupplierJpaEntity(s.id(), s.name(), s.phone(), s.email(), s.address(), s.note(),
                        s.active()));
            }
            em.flush();
        } catch (PersistenceException e) {
            if (PersistenceErrors.isUniqueViolation(e, "uq_suppliers_tenant_name")) {
                throw new SalesException(ErrorCode.SUPPLIER_EXISTS, "Nhà cung cấp đã tồn tại");
            }
            throw e;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Supplier> findById(UUID tenantId, UUID id) {
        return em.createQuery("select s from SupplierJpaEntity s where s.tenantId = :t and s.id = :id",
                        SupplierJpaEntity.class).setParameter("t", tenantId).setParameter("id", id)
                .getResultList().stream().findFirst().map(SupplierPersistenceAdapter::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByName(UUID tenantId, String name, UUID excludeId) {
        String jpql = "select count(s) from SupplierJpaEntity s where s.tenantId = :t and lower(s.name) = lower(:n)"
                + (excludeId != null ? " and s.id <> :ex" : "");
        var q = em.createQuery(jpql, Long.class).setParameter("t", tenantId).setParameter("n", name);
        if (excludeId != null) {
            q.setParameter("ex", excludeId);
        }
        return q.getSingleResult() > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<Supplier> search(UUID tenantId, String keyword, int page, int size) {
        boolean hasKeyword = keyword != null && !keyword.isBlank();
        String where = "from SupplierJpaEntity s where s.tenantId = :t"
                + (hasKeyword ? " and lower(s.name) like :kw escape '\\'" : "");
        var count = em.createQuery("select count(s) " + where, Long.class).setParameter("t", tenantId);
        var rows = em.createQuery("select s " + where + " order by lower(s.name), s.id", SupplierJpaEntity.class)
                .setParameter("t", tenantId);
        if (hasKeyword) {
            String kw = "%" + Like.escape(keyword.trim().toLowerCase(java.util.Locale.ROOT)) + "%";
            count.setParameter("kw", kw);
            rows.setParameter("kw", kw);
        }
        List<Supplier> items = rows.setFirstResult(page * size).setMaxResults(size).getResultList().stream()
                .map(SupplierPersistenceAdapter::toDomain).toList();
        return new PageResult<>(items, count.getSingleResult(), page, size);
    }

    private static Supplier toDomain(SupplierJpaEntity e) {
        return Supplier.restore(e.getId(), e.getTenantId(), e.getName(), e.getPhone(), e.getEmail(), e.getAddress(),
                e.getNote(), e.isActive());
    }
}
