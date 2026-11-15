package lms.identity.user.infrastructure.persistence;

import lms.identity.user.domain.DuplicateUserException;
import lms.identity.user.domain.TenantUser;
import lms.identity.user.domain.User;
import lms.identity.user.domain.UserCriteria;
import lms.identity.user.domain.UserPage;
import lms.identity.user.domain.UserRepository;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

/**
 * users là bảng toàn cục (không RLS/@TenantId): mọi truy vấn theo tenant lọc tường minh qua tenant_memberships
 * (UserMembershipJpaEntity, chỉ đọc) với tenantId truyền vào, nên không phụ thuộc lớp RLS.
 */
@Repository
class UserPersistenceAdapter implements UserRepository {

    private final SpringDataUserRepository jpa;
    private final SpringDataUserMembershipRepository memberships;

    UserPersistenceAdapter(SpringDataUserRepository jpa, SpringDataUserMembershipRepository memberships) {
        this.jpa = jpa;
        this.memberships = memberships;
    }

    @Override
    public User save(User u) {
        Instant now = Instant.now();
        Instant createdAt = jpa.findById(u.id()).map(UserJpaEntity::getCreatedAt).orElse(now);
        try {
            jpa.saveAndFlush(new UserJpaEntity(u.id(), u.username(), u.fullName(), u.email(), u.status(), createdAt, now));
        } catch (DataIntegrityViolationException e) {
            // unique(username) hoặc unique(lower(email)): hai yêu cầu đồng thời
            throw DuplicateUserException.generic();
        }
        return u;
    }

    @Override
    public Optional<User> findById(UUID id) {
        return jpa.findById(id).map(UserPersistenceAdapter::toDomain);
    }

    @Override
    public Optional<TenantUser> findInTenant(UUID id, UUID tenantId) {
        Optional<UserMembershipJpaEntity> membership = memberships.findById(new UserMembershipJpaEntity.Key(id, tenantId));
        if (membership.isEmpty()) return Optional.empty();
        return jpa.findById(id).map(e -> new TenantUser(toDomain(e), tenantId, membership.get().getDepartmentId()));
    }

    @Override
    public boolean existsById(UUID id) {
        return jpa.existsById(id);
    }

    @Override
    public boolean existsByUsername(String username) {
        return jpa.existsByUsername(username);
    }

    @Override
    public boolean existsByEmail(String email, UUID excludeId) {
        return jpa.existsByEmailExcluding(email, excludeId);
    }

    @Override
    public boolean hasMembership(UUID userId, UUID tenantId) {
        return memberships.existsById(new UserMembershipJpaEntity.Key(userId, tenantId));
    }

    @Override
    public int countMembershipsOf(UUID userId) {
        return (int) memberships.countByUserId(userId);
    }

    @Override
    public UserPage search(UserCriteria c) {
        Specification<UserJpaEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            // chỉ người có membership ở tenant (và phòng ban trong tenant đó nếu lọc)
            Subquery<UUID> members = query.subquery(UUID.class);
            Root<UserMembershipJpaEntity> m = members.from(UserMembershipJpaEntity.class);
            List<Predicate> memberPredicates = new ArrayList<>();
            memberPredicates.add(cb.equal(m.get("tenantId"), c.tenantId()));
            if (c.departmentId() != null) memberPredicates.add(cb.equal(m.get("departmentId"), c.departmentId()));
            members.select(m.get("userId")).where(memberPredicates.toArray(new Predicate[0]));
            predicates.add(root.get("id").in(members));
            if (c.keyword() != null) {
                String like = "%" + c.keyword().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("username")), like, '\\'),
                        cb.like(cb.lower(root.get("fullName")), like, '\\'),
                        cb.like(cb.lower(root.get("email")), like, '\\')));
            }
            if (c.status() != null) predicates.add(cb.equal(root.get("status"), c.status()));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Page<UserJpaEntity> page = jpa.findAll(spec, PageRequest.of(c.page(), c.size(), Sort.by("username")));
        List<UUID> ids = page.getContent().stream().map(UserJpaEntity::getId).toList();
        Map<UUID, UUID> departmentOf = new HashMap<>();
        if (!ids.isEmpty()) {
            memberships.findByTenantIdAndUserIdIn(c.tenantId(), ids)
                    .forEach(mb -> departmentOf.put(mb.getUserId(), mb.getDepartmentId()));
        }
        return new UserPage(page.getContent().stream()
                .map(e -> new TenantUser(toDomain(e), c.tenantId(), departmentOf.get(e.getId()))).toList(),
                page.getTotalElements(), c.page(), c.size());
    }

    private static User toDomain(UserJpaEntity e) {
        return User.restore(e.getId(), e.getUsername(), e.getFullName(), e.getEmail(), e.getStatus());
    }
}
