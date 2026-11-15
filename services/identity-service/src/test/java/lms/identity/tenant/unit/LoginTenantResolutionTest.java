package lms.identity.tenant.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lms.identity.tenant.application.AddMembershipUseCase;
import lms.identity.tenant.application.ListMyTenantsUseCase;
import lms.identity.tenant.application.ResolveLoginTenantUseCase;
import lms.identity.tenant.application.SwitchTenantUseCase;
import lms.identity.tenant.application.TenantChoice;
import lms.identity.tenant.application.TenantSummary;
import lms.identity.tenant.application.VerifyTenantAccessUseCase;
import lms.identity.tenant.domain.Membership;
import lms.identity.tenant.domain.MembershipRepository;
import lms.identity.tenant.domain.MembershipStatus;
import lms.identity.tenant.domain.NoTenantAccessException;
import lms.identity.tenant.domain.Tenant;
import lms.identity.tenant.domain.TenantRepository;
import lms.identity.tenant.domain.TenantStatus;
import org.junit.jupiter.api.Test;

/** Chọn tenant khi đăng nhập, đổi tenant, kiểm tra quyền truy cập tenant (mục 5.2, 5.7 của spec). */
class LoginTenantResolutionTest {

    private final FakeTenants tenants = new FakeTenants();
    private final FakeMemberships memberships = new FakeMemberships();
    private final ResolveLoginTenantUseCase resolve = new ResolveLoginTenantUseCase(tenants, memberships);
    private final SwitchTenantUseCase switchTenant = new SwitchTenantUseCase(tenants, memberships);
    private final VerifyTenantAccessUseCase verify = new VerifyTenantAccessUseCase(tenants, memberships);
    private final ListMyTenantsUseCase listMine = new ListMyTenantsUseCase(tenants, memberships);

    private final UUID user = UUID.randomUUID();
    private final Instant t0 = Instant.parse("2026-01-01T00:00:00Z");

    private Tenant tenant(String code, TenantStatus status) {
        Tenant t = Tenant.restore(UUID.randomUUID(), code, "Thư viện " + code, null, status, t0);
        tenants.byId.put(t.id(), t);
        return t;
    }

    private void join(Tenant t, MembershipStatus status, int dayOffset) {
        memberships.save(Membership.restore(user, t.id(), null, status, t0.plusSeconds(86400L * dayOffset)));
    }

    private String message(Runnable action) {
        try {
            action.run();
        } catch (NoTenantAccessException e) {
            return e.getMessage();
        }
        throw new AssertionError("phải ném NoTenantAccessException");
    }

    @Test
    void coMaTenantHopLeVaMembershipActiveThiChonTenantDoVaLuuLastTenant() {
        Tenant a = tenant("lib-a", TenantStatus.ACTIVE);
        Tenant b = tenant("lib-b", TenantStatus.ACTIVE);
        join(a, MembershipStatus.ACTIVE, 0);
        join(b, MembershipStatus.ACTIVE, 1);

        TenantChoice choice = resolve.execute(user, "  LIB-B ");

        assertThat(choice.tenantId()).isEqualTo(b.id());
        assertThat(choice.available()).extracting(TenantSummary::code).containsExactly("lib-a", "lib-b");
        assertThat(memberships.findLastTenant(user)).contains(b.id());
    }

    @Test
    void maSaiKhongCoMembershipMembershipTatHoacTenantTamNgungDeuBaoCungMotThongDiep() {
        Tenant a = tenant("lib-a", TenantStatus.ACTIVE);
        Tenant other = tenant("lib-other", TenantStatus.ACTIVE); // không là thành viên
        Tenant off = tenant("lib-off", TenantStatus.ACTIVE);
        Tenant suspended = tenant("lib-sus", TenantStatus.SUSPENDED);
        join(a, MembershipStatus.ACTIVE, 0);
        join(off, MembershipStatus.DISABLED, 1);
        join(suspended, MembershipStatus.ACTIVE, 2);

        String unknown = message(() -> resolve.execute(user, "khong-ton-tai"));
        String notMember = message(() -> resolve.execute(user, other.code()));
        String disabled = message(() -> resolve.execute(user, off.code()));
        String paused = message(() -> resolve.execute(user, suspended.code()));

        assertThat(List.of(notMember, disabled, paused)).allMatch(unknown::equals);
        assertThat(unknown).doesNotContain("khong-ton-tai").doesNotContainIgnoringCase("suspend");
        assertThat(memberships.findLastTenant(user)).isEmpty();
    }

    @Test
    void luaChonTenantKemPhongBanCuaMembershipOTenantDo() {
        Tenant a = tenant("lib-a", TenantStatus.ACTIVE);
        Tenant b = tenant("lib-b", TenantStatus.ACTIVE);
        UUID deptA = UUID.randomUUID();
        UUID deptB = UUID.randomUUID();
        memberships.save(Membership.restore(user, a.id(), deptA, MembershipStatus.ACTIVE, t0));
        memberships.save(Membership.restore(user, b.id(), deptB, MembershipStatus.ACTIVE, t0.plusSeconds(86400)));

        assertThat(resolve.execute(user, "lib-a").departmentId()).isEqualTo(deptA);
        assertThat(switchTenant.execute(user, b.id()).departmentId()).isEqualTo(deptB);
        assertThat(verify.execute(user, a.id()).departmentId()).isEqualTo(deptA);
    }

    @Test
    void khongCoMaThiDungLastTenantNeuConActive() {
        Tenant a = tenant("lib-a", TenantStatus.ACTIVE);
        Tenant b = tenant("lib-b", TenantStatus.ACTIVE);
        join(a, MembershipStatus.ACTIVE, 0);
        join(b, MembershipStatus.ACTIVE, 1);
        memberships.saveLastTenant(user, b.id());

        assertThat(resolve.execute(user, null).tenantId()).isEqualTo(b.id());
        assertThat(resolve.execute(user, "  ").tenantId()).isEqualTo(b.id());
    }

    @Test
    void lastTenantBiTamNgungHoacMembershipBiTatThiChonTenantThamGiaSomNhat() {
        Tenant early = tenant("lib-early", TenantStatus.ACTIVE);
        Tenant late = tenant("lib-late", TenantStatus.ACTIVE);
        Tenant last = tenant("lib-last", TenantStatus.ACTIVE);
        join(late, MembershipStatus.ACTIVE, 5); // lưu trước nhưng tham gia muộn hơn
        join(early, MembershipStatus.ACTIVE, 1);
        join(last, MembershipStatus.ACTIVE, 0);
        memberships.saveLastTenant(user, last.id());

        last.suspend();
        assertThat(resolve.execute(user, null).tenantId()).isEqualTo(early.id());

        last.activate();
        memberships.save(Membership.restore(user, last.id(), null, MembershipStatus.DISABLED, t0));
        memberships.saveLastTenant(user, last.id());
        assertThat(resolve.execute(user, null).tenantId()).isEqualTo(early.id());
    }

    @Test
    void tenantTamNgungBiBoQuaKhiChonVaKhongNamTrongDanhSach() {
        Tenant suspended = tenant("lib-sus", TenantStatus.SUSPENDED);
        Tenant active = tenant("lib-ok", TenantStatus.ACTIVE);
        join(suspended, MembershipStatus.ACTIVE, 0);
        join(active, MembershipStatus.ACTIVE, 3);

        TenantChoice choice = resolve.execute(user, null);

        assertThat(choice.tenantId()).isEqualTo(active.id());
        assertThat(choice.available()).extracting(TenantSummary::id).containsExactly(active.id());
    }

    @Test
    void khongConTenantNaoVaKhongPhaiPlatformAdminThiBaoCungThongDiep() {
        Tenant suspended = tenant("lib-sus", TenantStatus.SUSPENDED);
        join(suspended, MembershipStatus.ACTIVE, 0);
        String unknownCode = message(() -> resolve.execute(user, "khong-ton-tai"));

        assertThat(message(() -> resolve.execute(user, null))).isEqualTo(unknownCode);
        assertThat(message(() -> resolve.execute(user, null, false))).isEqualTo(unknownCode);
    }

    @Test
    void platformAdminKhongCoMembershipThiKhongCoTenant() {
        TenantChoice choice = resolve.execute(user, null, true);

        assertThat(choice.tenantId()).isNull();
        assertThat(choice.available()).isEmpty();
    }

    @Test
    void platformAdminVanBiTuChoiKhiChiRoMaKhongTruyCapDuoc() {
        tenant("lib-a", TenantStatus.ACTIVE);

        assertThatThrownBy(() -> resolve.execute(user, "lib-a", true)).isInstanceOf(NoTenantAccessException.class);
    }

    @Test
    void platformAdminCoMembershipThiVanChonTenant() {
        Tenant a = tenant("lib-a", TenantStatus.ACTIVE);
        join(a, MembershipStatus.ACTIVE, 0);

        assertThat(resolve.execute(user, null, true).tenantId()).isEqualTo(a.id());
    }

    @Test
    void doiTenantKiemTraMembershipVaTenantActiveRoiLuuLastTenant() {
        Tenant a = tenant("lib-a", TenantStatus.ACTIVE);
        Tenant b = tenant("lib-b", TenantStatus.ACTIVE);
        Tenant other = tenant("lib-other", TenantStatus.ACTIVE);
        Tenant suspended = tenant("lib-sus", TenantStatus.SUSPENDED);
        join(a, MembershipStatus.ACTIVE, 0);
        join(b, MembershipStatus.ACTIVE, 1);
        join(suspended, MembershipStatus.ACTIVE, 2);
        String expected = message(() -> resolve.execute(user, "khong-ton-tai"));

        TenantChoice choice = switchTenant.execute(user, b.id());

        assertThat(choice.tenantId()).isEqualTo(b.id());
        assertThat(choice.available()).extracting(TenantSummary::id).containsExactly(a.id(), b.id());
        assertThat(memberships.findLastTenant(user)).contains(b.id());
        assertThat(message(() -> switchTenant.execute(user, other.id()))).isEqualTo(expected);
        assertThat(message(() -> switchTenant.execute(user, suspended.id()))).isEqualTo(expected);
        assertThat(message(() -> switchTenant.execute(user, UUID.randomUUID()))).isEqualTo(expected);
        assertThat(memberships.findLastTenant(user)).contains(b.id());
    }

    @Test
    void kiemTraTruyCapTenantKhongLuuLastTenant() {
        Tenant a = tenant("lib-a", TenantStatus.ACTIVE);
        join(a, MembershipStatus.ACTIVE, 0);

        assertThat(verify.execute(user, a.id()).tenantId()).isEqualTo(a.id());
        assertThat(memberships.findLastTenant(user)).isEmpty();

        a.suspend();
        assertThatThrownBy(() -> verify.execute(user, a.id())).isInstanceOf(NoTenantAccessException.class);
    }

    @Test
    void danhSachTenantCuaToiChiGomTenantTruyCapDuocTheoThuTuThamGia() {
        Tenant a = tenant("lib-a", TenantStatus.ACTIVE);
        Tenant b = tenant("lib-b", TenantStatus.ACTIVE);
        Tenant off = tenant("lib-off", TenantStatus.ACTIVE);
        join(b, MembershipStatus.ACTIVE, 0);
        join(a, MembershipStatus.ACTIVE, 1);
        join(off, MembershipStatus.DISABLED, 2);

        assertThat(listMine.execute(user)).containsExactly(
                new TenantSummary(b.id(), "lib-b", "Thư viện lib-b"),
                new TenantSummary(a.id(), "lib-a", "Thư viện lib-a"));
    }

    @Test
    void themMembershipIdempotentVaKhongBatLaiMembershipDaTat() {
        AddMembershipUseCase add = new AddMembershipUseCase(tenants, memberships);
        Tenant a = tenant("lib-a", TenantStatus.ACTIVE);
        Tenant b = tenant("lib-b", TenantStatus.ACTIVE);
        join(b, MembershipStatus.DISABLED, 0);

        add.execute(user, a.id());
        add.execute(user, a.id());
        add.execute(user, b.id());

        assertThat(memberships.find(user, a.id())).get().extracting(Membership::status).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(memberships.find(user, b.id())).get().extracting(Membership::status).isEqualTo(MembershipStatus.DISABLED);
        assertThatThrownBy(() -> add.execute(user, UUID.randomUUID()))
                .isInstanceOf(lms.identity.tenant.domain.TenantNotFoundException.class);
    }

    // ---- fake repositories ----

    static class FakeTenants implements TenantRepository {
        final Map<UUID, Tenant> byId = new HashMap<>();

        public Tenant save(Tenant tenant) { byId.put(tenant.id(), tenant); return tenant; }
        public Optional<Tenant> findById(UUID id) { return Optional.ofNullable(byId.get(id)); }
        public Optional<Tenant> findByCode(String code) {
            return byId.values().stream().filter(t -> t.code().equals(code)).findFirst();
        }
        public List<Tenant> findAll() { return new ArrayList<>(byId.values()); }
    }

    static class FakeMemberships implements MembershipRepository {
        final Map<String, Membership> rows = new HashMap<>();
        final Map<UUID, UUID> last = new HashMap<>();

        public Membership save(Membership m) { rows.put(m.userId() + "/" + m.tenantId(), m); return m; }
        public List<Membership> findActiveByUser(UUID userId) {
            return rows.values().stream().filter(m -> m.userId().equals(userId) && m.isActive()).toList();
        }
        public Optional<Membership> find(UUID userId, UUID tenantId) {
            return Optional.ofNullable(rows.get(userId + "/" + tenantId));
        }
        public boolean existsForUser(UUID userId) {
            return rows.values().stream().anyMatch(m -> m.userId().equals(userId));
        }
        public void saveLastTenant(UUID userId, UUID tenantId) { last.put(userId, tenantId); }
        public Optional<UUID> findLastTenant(UUID userId) { return Optional.ofNullable(last.get(userId)); }
    }
}
