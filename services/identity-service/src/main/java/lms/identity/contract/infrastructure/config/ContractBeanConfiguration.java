package lms.identity.contract.infrastructure.config;

import java.time.Clock;
import lms.identity.contract.application.ContractPaymentUseCase;
import lms.identity.contract.application.ContractPolicy;
import lms.identity.contract.application.ContractQueryUseCase;
import lms.identity.contract.application.ContractRuntime;
import lms.identity.contract.application.GetMyContractsUseCase;
import lms.identity.contract.application.ManageContractUseCase;
import lms.identity.contract.application.ManualModuleChangeHistoryRecorder;
import lms.identity.contract.application.ProcessContractLifecycleUseCase;
import lms.identity.contract.domain.ContractHistoryRepository;
import lms.identity.contract.domain.ContractPaymentRepository;
import lms.identity.contract.domain.ContractReminderRepository;
import lms.identity.contract.domain.ContractRepository;
import lms.identity.tenant.application.ListActiveTenantAdminsQuery;
import lms.identity.tenant.application.ManualModuleChangeRecorder;
import lms.identity.tenant.application.PublishPlatformEventPort;
import lms.identity.tenant.application.TenantModuleOperations;
import lms.identity.tenant.application.TransactionalRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Composition root của context contract (adapter persistence tự đăng ký bằng @Repository). */
@Configuration
@EnableConfigurationProperties(ContractProperties.class)
public class ContractBeanConfiguration {

    @Bean
    ContractPolicy contractPolicy(ContractProperties properties) {
        return new ContractPolicy(properties.zone(), properties.gracePeriod(), properties.reminderDays());
    }

    @Bean
    ContractRuntime contractRuntime(TenantModuleOperations ops, TransactionalRunner tenantTransactionalRunner,
                                    PublishPlatformEventPort events, ContractRepository contracts,
                                    ContractHistoryRepository history, ContractPolicy policy, Clock clock) {
        return new ContractRuntime(ops, tenantTransactionalRunner, events, contracts, history, policy, clock);
    }

    @Bean
    ManageContractUseCase manageContractUseCase(ContractRepository contracts, ContractPaymentRepository payments,
                                                ContractRuntime runtime) {
        return new ManageContractUseCase(contracts, payments, runtime);
    }

    @Bean
    ContractPaymentUseCase contractPaymentUseCase(ContractRepository contracts, ContractPaymentRepository payments,
                                                  ContractRuntime runtime) {
        return new ContractPaymentUseCase(contracts, payments, runtime);
    }

    @Bean
    ContractQueryUseCase contractQueryUseCase(ContractRepository contracts, ContractPaymentRepository payments,
                                              ContractHistoryRepository history, TenantModuleOperations ops,
                                              ContractPolicy policy, Clock clock) {
        return new ContractQueryUseCase(contracts, payments, history, ops, policy, clock);
    }

    @Bean
    lms.identity.contract.application.PlatformOverviewUseCase platformOverviewUseCase(
            lms.identity.tenant.application.TenantOverviewQuery tenantOverview, ContractQueryUseCase contractQuery) {
        return new lms.identity.contract.application.PlatformOverviewUseCase(tenantOverview, contractQuery);
    }

    @Bean
    GetMyContractsUseCase getMyContractsUseCase(ContractRepository contracts, ContractPaymentRepository payments,
                                                ContractPolicy policy, Clock clock) {
        return new GetMyContractsUseCase(contracts, payments, policy, clock);
    }

    @Bean
    ProcessContractLifecycleUseCase processContractLifecycleUseCase(ContractRepository contracts,
                                                                    ContractReminderRepository reminders,
                                                                    ListActiveTenantAdminsQuery admins,
                                                                    ContractRuntime runtime) {
        return new ProcessContractLifecycleUseCase(contracts, reminders, admins, runtime);
    }

    /** Cổng ra của tenant: đường bật/tắt module tay ghi lịch sử "ngoại lệ ngoài hợp đồng" (R14). */
    @Bean
    ManualModuleChangeRecorder manualModuleChangeRecorder(ContractRepository contracts,
                                                          ContractHistoryRepository history,
                                                          ContractPolicy policy) {
        return new ManualModuleChangeHistoryRecorder(contracts, history, policy);
    }

    /** Bật lập lịch cho job vòng đời hợp đồng; tắt bằng {@code lms.contract.job.enabled=false} (test). */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "lms.contract.job.enabled", havingValue = "true", matchIfMissing = true)
    static class SchedulingConfiguration {
    }
}
