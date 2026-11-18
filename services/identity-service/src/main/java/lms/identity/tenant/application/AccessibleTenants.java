package lms.identity.tenant.application;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lms.identity.tenant.domain.Membership;
import lms.identity.tenant.domain.MembershipRepository;
import lms.identity.tenant.domain.ModuleCode;
import lms.identity.tenant.domain.Tenant;
import lms.identity.tenant.domain.TenantModuleRepository;
import lms.identity.tenant.domain.TenantRepository;

/**
 * "Có quyền ở tenant" = membership ACTIVE ở tenant ACTIVE (spec 5.2). Dùng chung cho mọi use case chọn/đổi/kiểm tra
 * tenant để quy tắc chỉ nằm một chỗ.
 */
final class AccessibleTenants {

    private AccessibleTenants() {
    }

    /** Tenant truy cập được, xếp theo thời điểm tham gia (sớm nhất trước), cùng thời điểm thì theo mã. */
    static List<Tenant> of(UUID userId, TenantRepository tenants, MembershipRepository memberships) {
        record Joined(Membership membership, Tenant tenant) {
        }
        List<Membership> active = memberships.findActiveByUser(userId).stream().filter(Membership::isActive).toList();
        if (active.isEmpty()) return List.of();
        // Nạp mọi tenant một lần (tránh N+1 theo số membership)
        Map<UUID, Tenant> byId = new java.util.HashMap<>();
        tenants.findAllByIds(active.stream().map(Membership::tenantId).toList())
                .forEach(t -> byId.put(t.id(), t));
        return active.stream()
                .map(m -> Optional.ofNullable(byId.get(m.tenantId())).filter(Tenant::isActive)
                        .map(t -> new Joined(m, t)))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing((Joined j) -> j.membership().joinedAt(),
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(j -> j.tenant().code()))
                .map(Joined::tenant)
                .toList();
    }

    /** Phòng ban của người dùng trong tenant (tenant_memberships.department_id), null nếu chưa gán. */
    static UUID departmentIn(UUID userId, UUID tenantId, MembershipRepository memberships) {
        return memberships.find(userId, tenantId).map(Membership::departmentId).orElse(null);
    }

    static List<TenantSummary> summaries(List<Tenant> accessible, TenantModuleRepository modules) {
        Map<UUID, List<ModuleCode>> enabled = modules.enabledOf(accessible.stream().map(Tenant::id).toList());
        return accessible.stream().map(t -> TenantSummary.of(t,
                enabled.getOrDefault(t.id(), List.of()).stream().map(ModuleCode::name).toList())).toList();
    }
}
