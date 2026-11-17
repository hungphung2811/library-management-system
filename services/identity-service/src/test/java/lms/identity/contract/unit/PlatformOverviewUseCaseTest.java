package lms.identity.contract.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.identity.contract.application.ContractQueryUseCase;
import lms.identity.contract.application.ContractResponses.ExpiringContractResponse;
import lms.identity.contract.application.PlatformOverviewUseCase;
import lms.identity.contract.application.PlatformOverviewUseCase.PlatformOverviewResponse;
import lms.identity.contract.domain.ContractException;
import lms.identity.tenant.application.TenantOverviewQuery;
import lms.identity.tenant.application.TenantOverviewQuery.TenantOverview;
import lms.identity.tenant.domain.ModuleCode;
import lms.identity.tenant.domain.Tenant;
import lms.identity.tenant.domain.TenantModuleRepository;
import lms.identity.tenant.domain.TenantRepository;
import org.junit.jupiter.api.Test;

/** Tổng quan nền tảng (ADR-0018): đếm tenant theo trạng thái, module tổng hợp, hợp đồng sắp hết hạn. Không Spring/DB. */
class PlatformOverviewUseCaseTest {

    private static ExpiringContractResponse expiring() {
        return new ExpiringContractResponse(UUID.randomUUID(), UUID.randomUUID(), "demo", "Demo", "Gói A",
                LocalDate.of(2026, 12, 5), 18, false, LocalDate.of(2026, 12, 12), 0);
    }

    @Test
    void tenantQueryDemTheoTrangThaiVaModuleChiCuaTenantDangHoatDong() {
        TenantRepository tenants = mock(TenantRepository.class);
        TenantModuleRepository modules = mock(TenantModuleRepository.class);
        Tenant a = Tenant.create("aa", "A", null);
        Tenant b = Tenant.create("bb", "B", null);
        Tenant c = Tenant.create("cc", "C", null);
        c.suspend();
        when(tenants.findAll()).thenReturn(List.of(a, b, c));
        when(modules.enabledOf(List.of(a.id(), b.id()))).thenReturn(Map.of(
                a.id(), List.of(ModuleCode.LENDING, ModuleCode.SALES), b.id(), List.of(ModuleCode.LENDING)));

        TenantOverview o = new TenantOverviewQuery(tenants, modules).execute();

        assertThat(o.total()).isEqualTo(3);
        assertThat(o.active()).isEqualTo(2);
        assertThat(o.suspended()).isEqualTo(1);
        assertThat(o.activeByModule()).containsEntry("LENDING", 2L).containsEntry("SALES", 1L);
    }

    @Test
    void khongCoTenantThiMoiModuleBang0() {
        TenantRepository tenants = mock(TenantRepository.class);
        when(tenants.findAll()).thenReturn(List.of());
        TenantOverview o = new TenantOverviewQuery(tenants, mock(TenantModuleRepository.class)).execute();
        assertThat(o.total()).isZero();
        assertThat(o.activeByModule()).containsEntry("LENDING", 0L).containsEntry("SALES", 0L);
    }

    @Test
    void ghepTenantVaHopDongSapHetHan() {
        TenantOverviewQuery tenantQuery = mock(TenantOverviewQuery.class);
        ContractQueryUseCase contracts = mock(ContractQueryUseCase.class);
        when(tenantQuery.execute()).thenReturn(new TenantOverview(3, 2, 1, Map.of("LENDING", 2L, "SALES", 1L)));
        ExpiringContractResponse e = expiring();
        when(contracts.expiring(20)).thenReturn(List.of(e));

        PlatformOverviewResponse r = new PlatformOverviewUseCase(tenantQuery, contracts).execute(20);

        assertThat(r.tenants().total()).isEqualTo(3);
        assertThat(r.tenants().active()).isEqualTo(2);
        assertThat(r.tenants().suspended()).isEqualTo(1);
        assertThat(r.withinDays()).isEqualTo(20);
        assertThat(r.expiringContractCount()).isEqualTo(1);
        assertThat(r.expiringContracts()).containsExactly(e);
        assertThat(r.activeTenantsByModule()).containsEntry("SALES", 1L);
    }

    @Test
    void khongTruyenWithinDaysThiDung30Ngay() {
        TenantOverviewQuery tenantQuery = mock(TenantOverviewQuery.class);
        ContractQueryUseCase contracts = mock(ContractQueryUseCase.class);
        when(tenantQuery.execute()).thenReturn(new TenantOverview(0, 0, 0, Map.of()));
        when(contracts.expiring(30)).thenReturn(List.of());

        PlatformOverviewResponse r = new PlatformOverviewUseCase(tenantQuery, contracts).execute(null);

        assertThat(r.withinDays()).isEqualTo(30);
        assertThat(r.expiringContractCount()).isZero();
    }

    @Test
    void withinDaysNgoaiKhoangLoiTuUseCaseHopDong() {
        TenantOverviewQuery tenantQuery = mock(TenantOverviewQuery.class);
        ContractQueryUseCase contracts = mock(ContractQueryUseCase.class);
        when(contracts.expiring(0)).thenThrow(new ContractException(ContractException.Reason.INVALID_CONTRACT, "x"));
        assertThatThrownBy(() -> new PlatformOverviewUseCase(tenantQuery, contracts).execute(0))
                .isInstanceOf(ContractException.class);
    }
}
