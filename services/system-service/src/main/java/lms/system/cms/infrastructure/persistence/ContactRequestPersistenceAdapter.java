package lms.system.cms.infrastructure.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.system.cms.application.ContactRequestRepositoryPort;
import lms.system.cms.domain.CmsErrorCode;
import lms.system.cms.domain.CmsException;
import lms.system.cms.domain.ContactRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

@Repository
class ContactRequestPersistenceAdapter implements ContactRequestRepositoryPort {

    private final SpringDataContactRequestRepository jpa;

    ContactRequestPersistenceAdapter(SpringDataContactRequestRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public ContactRequest save(ContactRequest request) {
        try {
            ContactRequestJpaEntity entity = jpa.findById(request.id()).orElse(null);
            if (entity == null) {
                entity = ContactRequestJpaEntity.create(request);
            } else {
                entity.apply(request);
            }
            return jpa.saveAndFlush(entity).toDomain();
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new CmsException(CmsErrorCode.CONTACT_REQUEST_VERSION_CONFLICT,
                    "Yêu cầu đã bị người khác sửa, hãy tải lại");
        }
    }

    @Override
    public Optional<ContactRequest> findById(UUID id) {
        return jpa.findById(id).map(ContactRequestJpaEntity::toDomain);
    }

    @Override
    public Page<ContactRequest> search(Criteria criteria) {
        Specification<ContactRequestJpaEntity> spec = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            if (criteria.status() != null) {
                predicates.add(cb.equal(root.get("status"), criteria.status()));
            }
            if (criteria.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.<Instant>get("createdAt"), criteria.from()));
            }
            if (criteria.to() != null) {
                predicates.add(cb.lessThan(root.<Instant>get("createdAt"), criteria.to()));
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        var result = jpa.findAll(spec, PageRequest.of(criteria.page(), criteria.size(),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"))));
        return new Page<>(result.getContent().stream().map(ContactRequestJpaEntity::toDomain).toList(),
                criteria.page(), criteria.size(), result.getTotalElements());
    }

    @Override
    public List<ContactRequest> findDueForAnonymization(Instant cutoff, int limit) {
        return jpa.findByAnonymizedAtIsNullAndCreatedAtBeforeOrderByCreatedAtAsc(cutoff, PageRequest.of(0, limit))
                .stream().map(ContactRequestJpaEntity::toDomain).toList();
    }
}
