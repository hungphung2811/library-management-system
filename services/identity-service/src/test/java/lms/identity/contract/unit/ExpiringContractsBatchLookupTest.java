package lms.identity.contract.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lms.identity.contract.application.ContractPolicy;
import lms.identity.contract.application.ContractQueryUseCase;
import lms.identity.contract.application.ContractResponses.ExpiringContractResponse;
import lms.identity.contract.domain.BillingCycle;
import lms.identity.contract.domain.Contract;
import lms.identity.contract.domain.ContractHistoryRepository;
import lms.identity.contract.domain.ContractModule;
import lms.identity.contract.domain.ContractPaymentRepository;
import lms.identity.contract.domain.ContractRepository;
import lms.identity.contract.domain.ContractStatus;
import lms.identity.tenant.application.TenantModuleOperations;
import lms.identity.tenant.domain.Tenant;
import lms.identity.tenant.domain.TenantModuleRepository;
import lms.identity.tenant.domain.TenantRepository;
import org.junit.jupiter.api.Test;

/** Danh sách hợp đồng sắp hết hạn tra tên tenant theo lô: một lần cho mọi tenant, không gọi từng tenant (N+1). */
class ExpiringContractsBatchLookupTest {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");

    private final ContractRepository contracts = mock(ContractRepository.class);
    private final ContractPaymentRepository payments = mock(ContractPaymentRepository.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final ContractPolicy policy = new ContractPolicy(ZoneId.of("Asia/Ho_Chi_Minh"), Duration.ofDays(7),
            List.of(30, 7));
    private final ContractQueryUseCase query = new ContractQueryUseCase(contracts, payments,
            mock(ContractHistoryRepository.class),
            new TenantModuleOperations(tenants, mock(TenantModuleRepository.class), null), policy,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private static Contract contractOf(UUID tenantId, String name) {
        Instant ends = NOW.plus(Duration.ofDays(10));
        return Contract.restore(UUID.randomUUID(), tenantId, name, ContractStatus.ACTIVE, false,
                NOW.minus(Duration.ofDays(100)), ends, 1_000_000L, new BillingCycle(BillingCycle.Unit.YEAR, 1), null,
                Set.of(ContractModule.LENDING), NOW, null, null, NOW, null, NOW, null, 0);
    }

    @Test
    void ganMoiTenantBangMotTruyVanDuNhieuHopDongCungTenant() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<Contract> rows = List.of(contractOf(a, "A1"), contractOf(b, "B1"), contractOf(a, "A2"),
                contractOf(UUID.randomUUID(), "Mất tenant"));
        when(contracts.findExpiring(any(), any())).thenReturn(rows);
        when(payments.paidByContracts(any())).thenReturn(Map.of());
        when(tenants.findAllByIds(any())).thenReturn(List.of(
                Tenant.create(a, "ta", "Tenant A", null), Tenant.create(b, "tb", "Tenant B", null)));

        List<ExpiringContractResponse> result = query.expiring(30);

        assertThat(result).hasSize(4);
        assertThat(result.get(0).tenantCode()).isEqualTo("ta");
        assertThat(result.get(1).tenantName()).isEqualTo("Tenant B");
        assertThat(result.get(2).tenantCode()).isEqualTo("ta");
        assertThat(result.get(3).tenantCode()).isNull(); // tenant không còn: giữ hành vi cũ, tên rỗng
        verify(tenants, times(1)).findAllByIds(any());
        verify(tenants, never()).findById(any());
    }

    @Test
    void khongCoHopDongThiKhongTruyVanTenant() {
        when(contracts.findExpiring(any(), any())).thenReturn(List.of());
        when(payments.paidByContracts(any())).thenReturn(Map.of());

        assertThat(query.expiring(null)).isEmpty();
        verify(tenants, never()).findAllByIds(any());
        verify(tenants, never()).findById(any());
    }
}
