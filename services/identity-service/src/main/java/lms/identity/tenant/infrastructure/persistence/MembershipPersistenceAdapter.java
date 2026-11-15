package lms.identity.tenant.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.identity.tenant.domain.Membership;
import lms.identity.tenant.domain.MembershipRepository;
import lms.identity.tenant.domain.MembershipStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Membership qua JPA; credentials.last_tenant_id đọc/ghi bằng SQL hẹp để không đụng entity của context auth.
 * Mọi tài khoản xác thực được (mật khẩu hoặc đăng nhập ngoài) đều có dòng credentials; với userId không có dòng
 * này (ví dụ chỉ có trong users, không thể nhận token) saveLastTenant là no-op có chủ đích và findLastTenant
 * trả Optional.empty.
 */
@Repository
class MembershipPersistenceAdapter implements MembershipRepository {

    private final SpringDataMembershipRepository jpa;
    private final JdbcTemplate jdbc;

    MembershipPersistenceAdapter(SpringDataMembershipRepository jpa, JdbcTemplate jdbc) {
        this.jpa = jpa;
        this.jdbc = jdbc;
    }

    @Override
    public Membership save(Membership m) {
        jpa.saveAndFlush(new MembershipJpaEntity(m.userId(), m.tenantId(), m.departmentId(), m.status(),
                m.joinedAt()));
        return m;
    }

    @Override
    public List<Membership> findActiveByUser(UUID userId) {
        return jpa.findByUserIdAndStatus(userId, MembershipStatus.ACTIVE).stream()
                .map(MembershipPersistenceAdapter::toDomain).toList();
    }

    @Override
    public Optional<Membership> find(UUID userId, UUID tenantId) {
        return jpa.findById(new MembershipId(userId, tenantId)).map(MembershipPersistenceAdapter::toDomain);
    }

    @Override
    public void saveLastTenant(UUID userId, UUID tenantId) {
        jdbc.update("UPDATE credentials SET last_tenant_id = ? WHERE user_id = ?", tenantId, userId);
    }

    @Override
    public Optional<UUID> findLastTenant(UUID userId) {
        List<UUID> rows = jdbc.query("SELECT last_tenant_id FROM credentials WHERE user_id = ? AND last_tenant_id IS NOT NULL",
                (rs, i) -> rs.getObject(1, UUID.class), userId);
        return rows.stream().findFirst();
    }

    private static Membership toDomain(MembershipJpaEntity e) {
        return Membership.restore(e.getUserId(), e.getTenantId(), e.getDepartmentId(), e.getStatus(),
                e.getJoinedAt());
    }
}
