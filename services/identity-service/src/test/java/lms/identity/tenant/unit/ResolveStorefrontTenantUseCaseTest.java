package lms.identity.tenant.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lms.identity.tenant.application.ResolveStorefrontTenantUseCase;
import lms.identity.tenant.domain.ModuleCode;
import lms.identity.tenant.domain.Tenant;
import lms.identity.tenant.domain.TenantRepository;
import lms.identity.tenant.domain.TenantStatus;
import org.junit.jupiter.api.Test;

/** Tra mã tenant cho storefront: chỉ ACTIVE và có SALES hoặc LENDING; mọi trường hợp khác cùng một kết quả rỗng (ADR-0011 2.5). */
class ResolveStorefrontTenantUseCaseTest {

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final InMemoryTenantModules modules = new InMemoryTenantModules();
    private final ResolveStorefrontTenantUseCase resolve = new ResolveStorefrontTenantUseCase(tenants, modules);

    private Tenant tenant(String code, TenantStatus status, ModuleCode... enabled) {
        Tenant t = Tenant.restore(UUID.randomUUID(), code, "Hiệu sách " + code, null, status,
                Instant.parse("2026-01-01T00:00:00Z"));
        when(tenants.findByCode(code)).thenReturn(Optional.of(t));
        modules.give(t.id(), enabled);
        return t;
    }

    @Test
    void activeVaCoSalesTraTenantVaModules() {
        Tenant t = tenant("shop-a", TenantStatus.ACTIVE, ModuleCode.LENDING, ModuleCode.SALES);

        var found = resolve.execute("shop-a");

        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(t.id());
        assertThat(found.get().code()).isEqualTo("shop-a");
        assertThat(found.get().name()).isEqualTo("Hiệu sách shop-a");
        assertThat(found.get().modules()).containsExactlyInAnyOrder("LENDING", "SALES");
    }

    @Test
    void chuanHoaMaTrimVaChuThuong() {
        tenant("shop-a", TenantStatus.ACTIVE, ModuleCode.SALES);
        when(tenants.findByCode("")).thenReturn(Optional.empty());

        assertThat(resolve.execute("  SHOP-A ")).isPresent();
    }

    @Test
    void biNgungThiRong() {
        tenant("shop-b", TenantStatus.SUSPENDED, ModuleCode.SALES);
        assertThat(resolve.execute("shop-b")).isEmpty();
    }

    @Test
    void chiCoLendingTraTenantVoiModulesLending() {
        tenant("shop-c", TenantStatus.ACTIVE, ModuleCode.LENDING);
        var found = resolve.execute("shop-c");
        assertThat(found).isPresent();
        assertThat(found.get().modules()).containsExactly("LENDING");
    }

    @Test
    void chiCoSalesTraTenantVoiModulesSales() {
        tenant("shop-s", TenantStatus.ACTIVE, ModuleCode.SALES);
        assertThat(resolve.execute("shop-s").orElseThrow().modules()).containsExactly("SALES");
    }

    @Test
    void lendingNhungBiNgungThiRong() {
        tenant("shop-l", TenantStatus.SUSPENDED, ModuleCode.LENDING);
        assertThat(resolve.execute("shop-l")).isEmpty();
    }

    @Test
    void khongCoModuleNaoThiRong() {
        tenant("shop-d", TenantStatus.ACTIVE);
        assertThat(resolve.execute("shop-d")).isEmpty();
    }

    @Test
    void moiNhanhGoiPortModuleDungMotLan() {
        var spyModules = org.mockito.Mockito.spy(modules);
        var uc = new ResolveStorefrontTenantUseCase(tenants, spyModules);
        tenant("ok", TenantStatus.ACTIVE, ModuleCode.SALES);
        tenant("sus", TenantStatus.SUSPENDED, ModuleCode.SALES);
        tenant("lend", TenantStatus.ACTIVE, ModuleCode.LENDING);
        when(tenants.findByCode("none")).thenReturn(Optional.empty());
        when(tenants.findByCode("")).thenReturn(Optional.empty());

        int calls = 0;
        for (String code : new String[] {"ok", "sus", "lend", "none", "", null}) {
            uc.execute(code);
            calls++;
            org.mockito.Mockito.verify(spyModules, org.mockito.Mockito.times(calls)).enabledOf(
                    org.mockito.ArgumentMatchers.anyCollection());
        }
    }

    @Test
    void khongTonTaiNullVaRongThiRong() {
        when(tenants.findByCode("khong-co")).thenReturn(Optional.empty());
        when(tenants.findByCode("")).thenReturn(Optional.empty());

        assertThat(resolve.execute("khong-co")).isEmpty();
        assertThat(resolve.execute(null)).isEmpty();
        assertThat(resolve.execute("   ")).isEmpty();
    }
}
