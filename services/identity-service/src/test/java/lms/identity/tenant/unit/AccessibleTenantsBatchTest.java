package lms.identity.tenant.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.identity.tenant.application.ListMyTenantsUseCase;
import lms.identity.tenant.application.TenantSummary;
import lms.identity.tenant.domain.Membership;
import lms.identity.tenant.domain.MembershipStatus;
import lms.identity.tenant.domain.Tenant;
import lms.identity.tenant.domain.TenantStatus;
import lms.identity.tenant.unit.LoginTenantResolutionTest.FakeMemberships;
import lms.identity.tenant.unit.LoginTenantResolutionTest.FakeTenants;
import org.junit.jupiter.api.Test;

/** ADR-0022 (B1): danh sách tenant truy cập được nạp theo lô, không gọi findById mỗi membership; kết quả không đổi. */
class AccessibleTenantsBatchTest {

    static class CountingTenants extends FakeTenants {
        int findByIdCalls;
        int batchCalls;

        @Override
        public Optional<Tenant> findById(UUID id) {
            findByIdCalls++;
            return super.findById(id);
        }

        @Override
        public List<Tenant> findAllByIds(Collection<UUID> ids) {
            batchCalls++;
            return ids.stream().distinct().map(byId::get).filter(java.util.Objects::nonNull).toList();
        }
    }

    private final CountingTenants tenants = new CountingTenants();
    private final FakeMemberships memberships = new FakeMemberships();
    private final InMemoryTenantModules modules = new InMemoryTenantModules();
    private final ListMyTenantsUseCase listMine = new ListMyTenantsUseCase(tenants, memberships, modules);
    private final UUID user = UUID.randomUUID();
    private final Instant t0 = Instant.parse("2026-01-01T00:00:00Z");

    private void join(String code, TenantStatus tenantStatus, MembershipStatus status, int day) {
        Tenant t = Tenant.restore(UUID.randomUUID(), code, "T " + code, null, tenantStatus, t0);
        tenants.byId.put(t.id(), t);
        memberships.save(Membership.restore(user, t.id(), null, status, t0.plusSeconds(86400L * day)));
    }

    @Test
    void nhieuMembershipChiGoiNapLoMotLanVaGiuThuTuLoc() {
        join("lib-c", TenantStatus.ACTIVE, MembershipStatus.ACTIVE, 2);
        join("lib-a", TenantStatus.ACTIVE, MembershipStatus.ACTIVE, 0);
        join("lib-b", TenantStatus.ACTIVE, MembershipStatus.ACTIVE, 1);
        join("lib-sus", TenantStatus.SUSPENDED, MembershipStatus.ACTIVE, 3);
        join("lib-off", TenantStatus.ACTIVE, MembershipStatus.DISABLED, 4);

        List<TenantSummary> result = listMine.execute(user);

        assertThat(result).extracting(TenantSummary::code).containsExactly("lib-a", "lib-b", "lib-c");
        assertThat(tenants.batchCalls).isEqualTo(1);
        assertThat(tenants.findByIdCalls).isZero();
    }

    @Test
    void khongCoMembershipKhongGoiNapTenant() {
        assertThat(listMine.execute(user)).isEmpty();
        assertThat(tenants.batchCalls).isZero();
        assertThat(tenants.findByIdCalls).isZero();
    }
}
