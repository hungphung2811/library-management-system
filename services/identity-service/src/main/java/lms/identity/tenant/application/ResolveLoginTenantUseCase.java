package lms.identity.tenant.application;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lms.identity.tenant.domain.MembershipRepository;
import lms.identity.tenant.domain.NoTenantAccessException;
import lms.identity.tenant.domain.Tenant;
import lms.identity.tenant.domain.TenantRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chọn tenant khi đăng nhập (spec 5.2, 5.7). Có mã tenant: phải truy cập được tenant đó. Không có mã: tenant dùng
 * gần nhất nếu còn truy cập được, nếu không thì tenant tham gia sớm nhất. Không còn tenant nào: từ chối, trừ platform
 * admin (nhận lựa chọn không có tenant). Mọi lần từ chối dùng cùng một {@link NoTenantAccessException}. Tenant được
 * chọn được ghi làm tenant dùng gần nhất.
 */
public class ResolveLoginTenantUseCase {

    private final TenantRepository tenants;
    private final MembershipRepository memberships;

    public ResolveLoginTenantUseCase(TenantRepository tenants, MembershipRepository memberships) {
        this.tenants = tenants;
        this.memberships = memberships;
    }

    public TenantChoice execute(UUID userId, String tenantCodeOrNull) {
        return execute(userId, tenantCodeOrNull, false);
    }

    /**
     * platformAdmin: tài khoản có cờ platform_admin; khi không chỉ rõ mã và không còn tenant nào thì trả lựa chọn
     * không có tenant thay vì từ chối. Chỉ rõ mã mà không truy cập được thì vẫn từ chối.
     */
    @Transactional
    public TenantChoice execute(UUID userId, String tenantCodeOrNull, boolean platformAdmin) {
        List<Tenant> accessible = AccessibleTenants.of(userId, tenants, memberships);
        List<TenantSummary> available = AccessibleTenants.summaries(accessible);
        String code = tenantCodeOrNull == null ? "" : tenantCodeOrNull.trim().toLowerCase(Locale.ROOT);
        Optional<UUID> last = memberships.findLastTenant(userId);

        Optional<Tenant> chosen;
        if (!code.isEmpty()) {
            chosen = accessible.stream().filter(t -> t.code().equals(code)).findFirst();
            if (chosen.isEmpty()) throw new NoTenantAccessException();
        } else {
            chosen = last.flatMap(id -> accessible.stream().filter(t -> t.id().equals(id)).findFirst())
                    .or(() -> accessible.stream().findFirst());
            if (chosen.isEmpty()) {
                if (platformAdmin) return new TenantChoice(null, List.of());
                throw new NoTenantAccessException();
            }
        }

        UUID tenantId = chosen.get().id();
        if (last.filter(tenantId::equals).isEmpty()) {
            memberships.saveLastTenant(userId, tenantId);
        }
        return new TenantChoice(tenantId, available);
    }
}
