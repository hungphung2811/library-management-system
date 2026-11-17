package lms.identity.tenant.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.identity.tenant.application.CreateTenantRequest;
import lms.identity.tenant.application.CreateTenantUseCase;
import lms.identity.tenant.application.DisableTenantModuleUseCase;
import lms.identity.tenant.application.EnableTenantModuleUseCase;
import lms.identity.tenant.application.ListTenantModulesUseCase;
import lms.identity.tenant.application.NewAdmin;
import lms.identity.tenant.application.TenantModuleResponse;
import lms.identity.tenant.application.TenantResponse;
import lms.identity.tenant.application.TransactionalRunner;
import lms.identity.tenant.domain.InvalidTenantModulesException;
import lms.identity.tenant.domain.ModuleCode;
import lms.identity.tenant.domain.PlatformOperationEvent;
import lms.identity.tenant.domain.TenantModuleEntry;
import lms.identity.tenant.domain.TenantModuleEvents;
import lms.identity.tenant.domain.TenantModuleRequiredException;
import lms.identity.tenant.domain.TenantModules;
import lms.identity.tenant.domain.TenantNotFoundException;
import lms.identity.tenant.domain.UnknownModuleException;
import lms.identity.tenant.unit.PlatformTenantUseCasesTest.FakeAccounts;
import lms.identity.tenant.unit.PlatformTenantUseCasesTest.FakeMemberships;
import lms.identity.tenant.unit.PlatformTenantUseCasesTest.FakeOrganizations;
import lms.identity.tenant.unit.PlatformTenantUseCasesTest.FakeRole;
import lms.identity.tenant.unit.PlatformTenantUseCasesTest.FakeRoles;
import lms.identity.tenant.unit.PlatformTenantUseCasesTest.FakeTenants;
import lms.identity.tenant.unit.PlatformTenantUseCasesTest.Recorded;
import lms.identity.tenant.unit.PlatformTenantUseCasesTest.RecordingEvents;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Module theo tenant (ADR-0009): quy tắc domain, tạo tenant theo module, bật/tắt, event và seed role mẫu. */
class TenantModulesUseCasesTest {

    private final FakeTenants tenants = new FakeTenants();
    private final FakeOrganizations organizations = new FakeOrganizations();
    private final FakeMemberships memberships = new FakeMemberships();
    private final FakeRoles roles = new FakeRoles();
    private final FakeAccounts accounts = new FakeAccounts(memberships);
    private final RecordingEvents events = new RecordingEvents();
    private final TransactionalRunner tx = new TransactionalRunner();
    private final InMemoryTenantModules modules = new InMemoryTenantModules();

    private final CreateTenantUseCase create = new CreateTenantUseCase(tenants, organizations, memberships, roles,
            accounts, events, tx, modules);
    private final EnableTenantModuleUseCase enable = new EnableTenantModuleUseCase(tenants, modules, roles, events, tx);
    private final DisableTenantModuleUseCase disable = new DisableTenantModuleUseCase(tenants, modules, events, tx);
    private final ListTenantModulesUseCase list = new ListTenantModulesUseCase(tenants, modules);

    private final UUID operator = UUID.randomUUID();

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private TenantResponse createLib(String code, List<String> requested) {
        return create.execute(new CreateTenantRequest(code, "Thư viện " + code, null,
                new NewAdmin(code + "-admin", "Quản trị", code + "@lib.vn", "Temp@12345"), null, requested), operator);
    }

    private List<String> roleNames(UUID tenantId) {
        return roles.rolesOf(tenantId).stream().map(FakeRole::name).toList();
    }

    private List<Recorded> moduleEvents() {
        return events.published.stream().filter(e -> e.event() instanceof TenantModuleEvents.Enabled
                || e.event() instanceof TenantModuleEvents.Disabled).toList();
    }

    private List<String> operations() {
        return events.published.stream().map(Recorded::event).filter(e -> e instanceof PlatformOperationEvent)
                .map(e -> ((PlatformOperationEvent) e).operation()).toList();
    }

    // ---------------------------------------------------------------- domain

    @Test
    void maModuleParseKhongPhanBietHoaThuongVaTuChoiMaLa() {
        assertThat(ModuleCode.parse(" sales ")).isEqualTo(ModuleCode.SALES);
        assertThatThrownBy(() -> ModuleCode.parse("PAYROLL")).isInstanceOf(UnknownModuleException.class);
        assertThatThrownBy(() -> ModuleCode.parse(" ")).isInstanceOf(UnknownModuleException.class);
        assertThatThrownBy(() -> ModuleCode.parse(null)).isInstanceOf(UnknownModuleException.class);
    }

    @Test
    void modulesBanDauMacDinhLaLendingRongBiTuChoiTrungDuocGop() {
        Instant at = Instant.parse("2026-11-16T00:00:00Z");
        assertThat(TenantModules.initial(null, at, null).enabled()).containsExactly(ModuleCode.LENDING);
        assertThat(TenantModules.initial(List.of("sales", "SALES", "lending"), at, null).enabled())
                .containsExactly(ModuleCode.LENDING, ModuleCode.SALES);
        assertThatThrownBy(() -> TenantModules.initial(List.of(), at, null))
                .isInstanceOf(InvalidTenantModulesException.class);
        assertThatThrownBy(() -> TenantModules.initial(List.of("LENDING", "XYZ"), at, null))
                .isInstanceOf(UnknownModuleException.class);
    }

    @Test
    void domainKhongChoTatModuleCuoiVaBatTatLaIdempotent() {
        Instant at = Instant.parse("2026-11-16T00:00:00Z");
        TenantModules m = TenantModules.restore(List.of(new TenantModuleEntry(ModuleCode.LENDING, true, at, null)));

        assertThat(m.enable(ModuleCode.LENDING, at, null)).isEmpty();
        assertThat(m.disable(ModuleCode.SALES, at, null)).isEmpty();
        assertThatThrownBy(() -> m.disable(ModuleCode.LENDING, at, null))
                .isInstanceOf(TenantModuleRequiredException.class);

        assertThat(m.enable(ModuleCode.SALES, at, null)).isPresent();
        assertThat(m.disable(ModuleCode.LENDING, at, null)).isPresent();
        assertThat(m.enabled()).containsExactly(ModuleCode.SALES);
        assertThatThrownBy(() -> m.disable(ModuleCode.SALES, at, null))
                .isInstanceOf(TenantModuleRequiredException.class);
    }

    // ---------------------------------------------------------------- tạo tenant

    @Test
    void taoTenantKhongNeuModuleMacDinhLendingVaChepMauLoiCongMauLending() {
        TenantResponse created = createLib("lib-a", null);

        assertThat(created.modules()).containsExactly("LENDING");
        assertThat(modules.findByTenant(created.id()).enabled()).containsExactly(ModuleCode.LENDING);
        assertThat(modules.findByTenant(created.id()).entry(ModuleCode.LENDING).updatedBy()).isEqualTo(operator);
        assertThat(roleNames(created.id())).containsExactlyInAnyOrder("SUPER_ADMIN", "LIBRARIAN", "MEMBER", "READER",
                "AUDITOR");
        assertThat(roles.seededModules).containsExactly("LENDING");
    }

    @Test
    void taoTenantVoiSalesChiChepMauLoiCongMauSales() {
        TenantResponse created = createLib("shop-a", List.of("SALES"));

        assertThat(created.modules()).containsExactly("SALES");
        assertThat(roleNames(created.id())).containsExactlyInAnyOrder("SUPER_ADMIN", "CASHIER", "STOCKKEEPER");
    }

    @Test
    void taoTenantVoiCaHaiModule() {
        TenantResponse created = createLib("both", List.of("LENDING", "SALES"));

        assertThat(created.modules()).containsExactly("LENDING", "SALES");
        assertThat(roleNames(created.id())).hasSize(7);
    }

    @Test
    void taoTenantModulesRongBiTuChoiVaKhongGhiGiHet() {
        assertThatThrownBy(() -> createLib("lib-empty", List.of())).isInstanceOf(InvalidTenantModulesException.class);
        assertThatThrownBy(() -> createLib("lib-bad", List.of("PAYROLL"))).isInstanceOf(UnknownModuleException.class);

        assertThat(tenants.byId).isEmpty();
        assertThat(modules.rows).isEmpty();
        assertThat(events.published).isEmpty();
    }

    // ---------------------------------------------------------------- bật module

    @Test
    void batSalesChepMauSalesGhiTenantModulesVaPhatEventSauCommit() {
        TenantResponse created = createLib("lib-b", null);
        events.published.clear();
        roles.seededInContext.clear();

        TenantModuleResponse response = enable.execute(created.id(), "SALES", operator);

        assertThat(response.module()).isEqualTo("SALES");
        assertThat(response.enabled()).isTrue();
        assertThat(response.updatedBy()).isEqualTo(operator);
        assertThat(response.updatedAt()).isNotNull();
        assertThat(modules.findByTenant(created.id()).enabled()).containsExactly(ModuleCode.LENDING, ModuleCode.SALES);
        // chỉ chép mẫu của module SALES, không chép lại mẫu lõi
        assertThat(roleNames(created.id())).containsExactlyInAnyOrder("SUPER_ADMIN", "LIBRARIAN", "MEMBER", "READER",
                "AUDITOR", "CASHIER", "STOCKKEEPER");
        assertThat(roles.seededInContext).containsExactly(created.id());
        // event chạy trong envelope của tenant, đúng loại, có audit
        assertThat(moduleEvents()).singleElement().satisfies(e -> {
            assertThat(e.event().eventType()).isEqualTo("TenantModuleEnabled");
            TenantModuleEvents.Enabled ev = (TenantModuleEvents.Enabled) e.event();
            assertThat(ev.tenantId()).isEqualTo(created.id());
            assertThat(ev.module()).isEqualTo("SALES");
            assertThat(ev.actorId()).isEqualTo(operator);
            assertThat(e.envelopeTenant()).isEqualTo(created.id());
        });
        assertThat(operations()).containsExactly("TENANT_MODULE_ENABLED");
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void batLanHaiKhongNhanBanRoleKhongPhatEventVaKhongDoiThoiDiem() {
        TenantResponse created = createLib("lib-c", null);
        TenantModuleResponse first = enable.execute(created.id(), "SALES", operator);
        int rolesAfterFirst = roles.rolesOf(created.id()).size();
        events.published.clear();

        TenantModuleResponse second = enable.execute(created.id(), "sales", UUID.randomUUID());

        assertThat(second).isEqualTo(first);
        assertThat(roles.rolesOf(created.id())).hasSize(rolesAfterFirst);
        assertThat(events.published).isEmpty();
    }

    @Test
    void batLaiSauKhiTatKhongGhiDeRoleDaCo() {
        TenantResponse created = createLib("lib-d", List.of("LENDING", "SALES"));
        // quản trị tenant đã xóa role CASHIER và giữ STOCKKEEPER
        roles.roles.removeIf(r -> r.tenantId().equals(created.id()) && r.name().equals("CASHIER"));
        disable.execute(created.id(), "SALES", operator);

        enable.execute(created.id(), "SALES", operator);

        // CASHIER được chép lại, STOCKKEEPER giữ nguyên (không nhân đôi)
        assertThat(roleNames(created.id()).stream().filter("STOCKKEEPER"::equals).count()).isEqualTo(1);
        assertThat(roleNames(created.id())).contains("CASHIER");
    }

    @Test
    void batModuleChoTenantKhongTonTaiVaMaModuleSai() {
        assertThatThrownBy(() -> enable.execute(UUID.randomUUID(), "SALES", operator))
                .isInstanceOf(TenantNotFoundException.class);
        TenantResponse created = createLib("lib-e", null);
        assertThatThrownBy(() -> enable.execute(created.id(), "PAYROLL", operator))
                .isInstanceOf(UnknownModuleException.class);
    }

    // ---------------------------------------------------------------- tắt module

    @Test
    void tatModuleGiuDongKhongXoaRoleVaPhatEvent() {
        TenantResponse created = createLib("lib-f", List.of("LENDING", "SALES"));
        int rolesBefore = roles.rolesOf(created.id()).size();
        events.published.clear();

        TenantModuleResponse response = disable.execute(created.id(), "SALES", operator);

        assertThat(response.enabled()).isFalse();
        assertThat(modules.findByTenant(created.id()).enabled()).containsExactly(ModuleCode.LENDING);
        assertThat(modules.findByTenant(created.id()).entry(ModuleCode.SALES).enabled()).isFalse();
        assertThat(roles.rolesOf(created.id())).hasSize(rolesBefore);
        assertThat(moduleEvents()).singleElement().satisfies(e -> {
            assertThat(e.event().eventType()).isEqualTo("TenantModuleDisabled");
            assertThat(e.envelopeTenant()).isEqualTo(created.id());
        });
        assertThat(operations()).containsExactly("TENANT_MODULE_DISABLED");
    }

    @Test
    void tatModuleCuoiCungBiTuChoiVaKhongGhiHayPhatEvent() {
        TenantResponse created = createLib("lib-g", null);
        events.published.clear();

        assertThatThrownBy(() -> disable.execute(created.id(), "LENDING", operator))
                .isInstanceOf(TenantModuleRequiredException.class);

        assertThat(modules.findByTenant(created.id()).enabled()).containsExactly(ModuleCode.LENDING);
        assertThat(events.published).isEmpty();
    }

    @Test
    void tatModuleDangTatLaNoOp() {
        TenantResponse created = createLib("lib-h", null);
        events.published.clear();

        TenantModuleResponse response = disable.execute(created.id(), "SALES", operator);

        assertThat(response.enabled()).isFalse();
        assertThat(response.updatedAt()).isNull();
        assertThat(events.published).isEmpty();
    }

    @Test
    void tatLendingKhiConSalesDuocPhepVaNguocLai() {
        TenantResponse created = createLib("lib-i", List.of("LENDING", "SALES"));

        disable.execute(created.id(), "LENDING", operator);
        assertThat(modules.findByTenant(created.id()).enabled()).containsExactly(ModuleCode.SALES);
        assertThatThrownBy(() -> disable.execute(created.id(), "SALES", operator))
                .isInstanceOf(TenantModuleRequiredException.class);
    }

    // ---------------------------------------------------------------- đọc

    @Test
    void danhSachTraMoiModuleKemTrangThaiVaMaDangBatChoToken() {
        TenantResponse created = createLib("lib-j", null);

        List<TenantModuleResponse> all = list.execute(created.id());
        assertThat(all).extracting(TenantModuleResponse::module).containsExactly("LENDING", "SALES");
        assertThat(all).extracting(TenantModuleResponse::enabled).containsExactly(true, false);
        assertThat(list.enabledCodes(created.id())).containsExactly("LENDING");

        enable.execute(created.id(), "SALES", operator);
        assertThat(list.enabledCodes(created.id())).containsExactly("LENDING", "SALES");
        assertThatThrownBy(() -> list.execute(UUID.randomUUID())).isInstanceOf(TenantNotFoundException.class);
    }

    @Test
    void domainVaSecurityCommonCungTapModule() {
        assertThat(java.util.Arrays.stream(ModuleCode.values()).map(Enum::name).toList())
                .containsExactlyElementsOf(java.util.Arrays.stream(lms.security.permission.TenantModule.values())
                        .map(Enum::name).toList());
    }
}
