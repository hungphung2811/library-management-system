package lms.identity.apiclient.infrastructure.config;

import java.util.UUID;
import lms.identity.apiclient.application.TenantContractGate;
import lms.identity.contract.application.GetMyContractsUseCase;
import lms.tenant.TenantContext;

/**
 * Kiểm hợp đồng của tenant bằng use case sẵn có của context contract (ADR-0012), không dựng truy vấn riêng: tenant có hợp
 * đồng ACTIVE đang IN_EFFECT hoặc IN_GRACE, cùng tiêu chí với {@code GetMyContractsUseCase#current}. Dùng
 * {@code list()} (không ném lỗi khi không có hợp đồng) và đặt TenantContext theo tenant cần kiểm (đường platform admin
 * không có TenantContext).
 */
class ContractBackedTenantContractGate implements TenantContractGate {

    private final GetMyContractsUseCase contracts;

    ContractBackedTenantContractGate(GetMyContractsUseCase contracts) {
        this.contracts = contracts;
    }

    @Override
    public boolean hasActiveContract(UUID tenantId) {
        return TenantContext.callAs(tenantId, () -> contracts.list().stream()
                .anyMatch(c -> "ACTIVE".equals(c.status())
                        && ("IN_EFFECT".equals(c.phase()) || "IN_GRACE".equals(c.phase()))));
    }
}
