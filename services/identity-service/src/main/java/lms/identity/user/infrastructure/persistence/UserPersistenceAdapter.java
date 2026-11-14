package lms.identity.user.infrastructure.persistence;

import lms.identity.user.domain.DuplicateUserException;
import lms.identity.user.domain.User;
import lms.identity.user.domain.UserCriteria;
import lms.identity.user.domain.UserPage;
import lms.identity.user.domain.UserRepository;
import jakarta.persistence.criteria.Predicate;
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

@Repository
class UserPersistenceAdapter implements UserRepository {

    private final SpringDataUserRepository jpa;

    UserPersistenceAdapter(SpringDataUserRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public User save(User u) {
        Instant now = Instant.now();
        Instant createdAt = jpa.findById(u.id()).map(UserJpaEntity::getCreatedAt).orElse(now);
        try {
            jpa.saveAndFlush(new UserJpaEntity(u.id(), u.username(), u.fullName(), u.email(), u.departmentId(),
                    u.status(), createdAt, now));
        } catch (DataIntegrityViolationException e) {
            // unique(username) hoặc unique(lower(email)): hai yêu cầu đồng thời
            throw new DuplicateUserException("Tên đăng nhập hoặc email đã tồn tại");
        }
        return u;
    }

    @Override
    public Optional<User> findById(UUID id) {
        return jpa.findById(id).map(UserPersistenceAdapter::toDomain);
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
    public UserPage search(UserCriteria c) {
        Specification<UserJpaEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (c.keyword() != null) {
                String like = "%" + c.keyword().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("username")), like, '\\'),
                        cb.like(cb.lower(root.get("fullName")), like, '\\'),
                        cb.like(cb.lower(root.get("email")), like, '\\')));
            }
            if (c.departmentId() != null) predicates.add(cb.equal(root.get("departmentId"), c.departmentId()));
            if (c.status() != null) predicates.add(cb.equal(root.get("status"), c.status()));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Page<UserJpaEntity> page = jpa.findAll(spec, PageRequest.of(c.page(), c.size(), Sort.by("username")));
        return new UserPage(page.getContent().stream().map(UserPersistenceAdapter::toDomain).toList(),
                page.getTotalElements(), c.page(), c.size());
    }

    private static User toDomain(UserJpaEntity e) {
        return User.restore(e.getId(), e.getUsername(), e.getFullName(), e.getEmail(), e.getDepartmentId(), e.getStatus());
    }
}
