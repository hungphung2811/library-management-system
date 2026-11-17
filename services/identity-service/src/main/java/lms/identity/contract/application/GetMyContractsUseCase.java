package lms.identity.contract.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.identity.contract.application.ContractResponses.TenantContractView;
import lms.identity.contract.domain.Contract;
import lms.identity.contract.domain.ContractException;
import lms.identity.contract.domain.ContractException.Reason;
import lms.identity.contract.domain.ContractPaymentRepository;
import lms.identity.contract.domain.ContractPhase;
import lms.identity.contract.domain.ContractRepository;
import lms.identity.contract.domain.ContractStatus;
import lms.tenant.TenantContext;

/**
 * Tenant admin xem hợp đồng CỦA MÌNH (chỉ đọc, ADR-0012 mục 2.5.2). Bảng toàn cục không có RLS nên tenant lấy từ
 * {@code TenantContext.require()} (không nhận id nào từ client) và lọc {@code tenant_id} tường minh: không có đường
 * đọc hợp đồng tenant khác. Không trả NHÁP, ghi chú nội bộ, thanh toán chi tiết; tiền chỉ là tổng.
 */
public class GetMyContractsUseCase {

    private final ContractRepository contracts;
    private final ContractPaymentRepository payments;
    private final ContractPolicy policy;
    private final Clock clock;

    public GetMyContractsUseCase(ContractRepository contracts, ContractPaymentRepository payments,
                                 ContractPolicy policy, Clock clock) {
        this.contracts = contracts;
        this.payments = payments;
        this.policy = policy;
        this.clock = clock;
    }

    /** Mọi hợp đồng không phải NHÁP, mới nhất trước. */
    public List<TenantContractView> list() {
        UUID tenantId = TenantContext.require();
        Instant now = clock.instant();
        List<Contract> rows = contracts.findByTenant(tenantId).stream()
                .filter(c -> c.tenantId().equals(tenantId) && c.status() != ContractStatus.DRAFT).toList();
        Map<UUID, Long> paid = payments.paidByContracts(rows.stream().map(Contract::id).toList());
        return rows.stream().map(c -> ContractResponses.tenantView(c, paid.getOrDefault(c.id(), 0L), policy, now))
                .toList();
    }

    /** Hợp đồng đang IN_EFFECT hoặc IN_GRACE; không có thì 404 CONTRACT_NOT_FOUND. */
    public TenantContractView current() {
        UUID tenantId = TenantContext.require();
        Instant now = clock.instant();
        Contract current = contracts.findByTenant(tenantId).stream()
                .filter(c -> c.tenantId().equals(tenantId))
                .filter(c -> {
                    ContractPhase phase = c.phase(now, policy.gracePeriod());
                    return phase == ContractPhase.IN_EFFECT || phase == ContractPhase.IN_GRACE;
                })
                .findFirst()
                .orElseThrow(() -> new ContractException(Reason.CONTRACT_NOT_FOUND,
                        "Tenant chưa có hợp đồng đang hiệu lực"));
        long paid = payments.paidByContracts(List.of(current.id())).getOrDefault(current.id(), 0L);
        return ContractResponses.tenantView(current, paid, policy, now);
    }
}
