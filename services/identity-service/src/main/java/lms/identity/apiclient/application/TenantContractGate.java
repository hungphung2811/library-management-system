package lms.identity.apiclient.application;

import java.util.UUID;

/**
 * Cổng kiểm hợp đồng thuê dịch vụ (ADR-0012) của tenant: Application INTEGRATION chỉ tạo được khi tenant có hợp đồng
 * đang hiệu lực (ACTIVE, pha IN_EFFECT hoặc IN_GRACE). Adapter dùng use case sẵn có của context contract.
 */
public interface TenantContractGate {

    boolean hasActiveContract(UUID tenantId);
}
