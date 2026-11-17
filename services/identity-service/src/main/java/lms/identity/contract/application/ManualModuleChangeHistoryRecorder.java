package lms.identity.contract.application;

import static lms.identity.contract.application.ContractTransitions.data;

import java.time.Instant;
import java.util.UUID;
import lms.identity.contract.domain.Contract;
import lms.identity.contract.domain.ContractHistoryEntry;
import lms.identity.contract.domain.ContractHistoryRepository;
import lms.identity.contract.domain.ContractModule;
import lms.identity.contract.domain.ContractRepository;
import lms.identity.tenant.application.ManualModuleChangeRecorder;

/**
 * Ghi lịch sử "ngoại lệ ngoài hợp đồng" khi platform admin bật/tắt module tay (ADR-0012 mục 2.3.4, R14): gắn vào
 * hợp đồng đang áp dụng (null nếu không có), {@code outsideContract} = module không thuộc hợp đồng đó. Chạy trong cùng
 * transaction với thay đổi module.
 */
public class ManualModuleChangeHistoryRecorder implements ManualModuleChangeRecorder {

    private final ContractRepository contracts;
    private final ContractHistoryRepository history;
    private final ContractPolicy policy;

    public ManualModuleChangeHistoryRecorder(ContractRepository contracts, ContractHistoryRepository history,
                                             ContractPolicy policy) {
        this.contracts = contracts;
        this.history = history;
        this.policy = policy;
    }

    @Override
    public void recordManualChange(UUID tenantId, String module, boolean enabled, UUID actorId, Instant at) {
        Contract applying = contracts.findActiveByTenant(tenantId).stream()
                .filter(c -> c.modulesApplied() && c.covers(at, policy.gracePeriod()))
                .findFirst().orElse(null);
        boolean outside = applying == null || !applying.modules().contains(ContractModule.parse(module));
        history.append(ContractHistoryEntry.by(actorId, tenantId, applying == null ? null : applying.id(),
                enabled ? "MANUAL_MODULE_ENABLED" : "MANUAL_MODULE_DISABLED", at,
                data("module", module, "outsideContract", outside)));
    }
}
