package lms.identity.contract.application;

import java.util.List;
import java.util.Map;
import lms.identity.contract.application.ContractResponses.ExpiringContractResponse;
import lms.identity.tenant.application.TenantOverviewQuery;
import lms.identity.tenant.application.TenantOverviewQuery.TenantOverview;

/**
 * Tổng quan nền tảng cho platform admin (ADR-0018 mục 2.1): số tenant theo trạng thái, module đang bật (tổng hợp) và hợp
 * đồng sắp hết hạn. Không có số liệu nghiệp vụ của tenant. Tái dùng {@link ContractQueryUseCase#expiring} nên cùng
 * định nghĩa "sắp hết hạn" (gồm đang ân hạn) và cùng giới hạn {@code withinDays}.
 */
public class PlatformOverviewUseCase {

    public record TenantCounts(long total, long active, long suspended) {
    }

    public record PlatformOverviewResponse(TenantCounts tenants, Map<String, Long> activeTenantsByModule,
                                           int withinDays, int expiringContractCount,
                                           List<ExpiringContractResponse> expiringContracts) {
    }

    private final TenantOverviewQuery tenantOverview;
    private final ContractQueryUseCase contracts;

    public PlatformOverviewUseCase(TenantOverviewQuery tenantOverview, ContractQueryUseCase contracts) {
        this.tenantOverview = tenantOverview;
        this.contracts = contracts;
    }

    /** @param withinDays null = mặc định {@link ContractQueryUseCase#DEFAULT_EXPIRING_DAYS}; ngoài 1..365 thì lỗi. */
    public PlatformOverviewResponse execute(Integer withinDays) {
        int days = withinDays == null ? ContractQueryUseCase.DEFAULT_EXPIRING_DAYS : withinDays;
        List<ExpiringContractResponse> expiring = contracts.expiring(days);
        TenantOverview t = tenantOverview.execute();
        return new PlatformOverviewResponse(new TenantCounts(t.total(), t.active(), t.suspended()),
                t.activeByModule(), days, expiring.size(), expiring);
    }
}
