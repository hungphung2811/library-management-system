package lms.identity.contract.application;

import java.time.Clock;
import lms.identity.contract.domain.ContractHistoryRepository;
import lms.identity.contract.domain.ContractRepository;
import lms.identity.tenant.application.PublishPlatformEventPort;
import lms.identity.tenant.application.TenantModuleOperations;
import lms.identity.tenant.application.TransactionalRunner;

/** Gói phụ thuộc dùng chung của các use case ghi hợp đồng (khung transaction, các điểm chuyển, cấu hình, đồng hồ). */
public final class ContractRuntime {

    private final ContractExecutor executor;
    private final ContractTransitions transitions;
    private final ContractPolicy policy;
    private final Clock clock;

    public ContractRuntime(TenantModuleOperations ops, TransactionalRunner tx, PublishPlatformEventPort events,
                           ContractRepository contracts, ContractHistoryRepository history, ContractPolicy policy,
                           Clock clock) {
        this.executor = new ContractExecutor(ops, tx, events);
        this.transitions = new ContractTransitions(ops, contracts, history, policy);
        this.policy = policy;
        this.clock = clock;
    }

    ContractExecutor executor() {
        return executor;
    }

    ContractTransitions transitions() {
        return transitions;
    }

    ContractPolicy policy() {
        return policy;
    }

    Clock clock() {
        return clock;
    }
}
