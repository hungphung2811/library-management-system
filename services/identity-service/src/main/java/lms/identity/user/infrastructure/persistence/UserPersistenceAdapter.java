package lms.identity.user.infrastructure.persistence;

import lms.identity.user.domain.DuplicateUserException;
import lms.identity.user.domain.TenantUser;
import lms.identity.user.domain.User;
import lms.identity.user.domain.UserCriteria;
import lms.identity.user.domain.UserPage;
import lms.identity.user.domain.UserRepository;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Join;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
    public List<User> findAllInTenant(java.util.Collection<UUID> ids, UUID tenantId) {
        if (ids.isEmpty()) return List.of();
        List<UUID> members = memberships.findByTenantIdAndUserIdIn(tenantId, ids).stream()
                .map(UserMembershipJpaEntity::getUserId).toList();
        return jpa.findAllById(members).stream().map(UserPersistenceAdapter::toDomain).toList();
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
        // Truy vấn theo dòng membership (tenant_memberships join users): tenantId có giá trị thì một tenant (mỗi người một
        // dòng), null thì mọi tenant (phạm vi ALL của platform admin, ADR-0008): mỗi dòng là một cặp (user, tenant) và
        // người không có membership không xuất hiện. users và tenant_memberships là bảng toàn cục, không phụ thuộc RLS.
        Specification<UserMembershipJpaEntity> spec = (root, query, cb) -> {
            boolean count = query.getResultType() == Long.class || query.getResultType() == long.class;
            // truy vấn dữ liệu nạp luôn hồ sơ (tránh N+1); truy vấn đếm chỉ join
            @SuppressWarnings("unchecked")
            Join<UserMembershipJpaEntity, UserJpaEntity> user = count ? root.join("user")
                    : (Join<UserMembershipJpaEntity, UserJpaEntity>) (Object) root.fetch("user");
            List<Predicate> predicates = new ArrayList<>();
            if (c.tenantId() != null) predicates.add(cb.equal(root.get("tenantId"), c.tenantId()));
            if (c.departmentId() != null) predicates.add(cb.equal(root.get("departmentId"), c.departmentId()));
            if (c.keyword() != null) {
                String like = "%" + c.keyword().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(user.get("username")), like, '\\'),
                        cb.like(cb.lower(user.get("fullName")), like, '\\'),
                        cb.like(cb.lower(user.get("email")), like, '\\')));
            }
            if (c.status() != null) predicates.add(cb.equal(user.get("status"), c.status()));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Page<UserMembershipJpaEntity> page = memberships.findAll(spec,
                PageRequest.of(c.page(), c.size(), Sort.by("user.username", "tenantId")));
        return new UserPage(page.getContent().stream()
                .map(m -> new TenantUser(toDomain(m.getUser()), m.getTenantId(), m.getDepartmentId())).toList(),
                page.getTotalElements(), c.page(), c.size());
    }

    private static User toDomain(UserJpaEntity e) {
        return User.restore(e.getId(), e.getUsername(), e.getFullName(), e.getEmail(), e.getStatus());
    }
}
