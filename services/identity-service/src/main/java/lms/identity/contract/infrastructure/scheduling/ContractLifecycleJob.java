package lms.identity.contract.infrastructure.scheduling;

import lms.identity.contract.application.ProcessContractLifecycleUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Job vòng đời hợp đồng mỗi giờ (phút 7, tránh đỉnh giờ tròn, múi giờ nghiệp vụ). Chỉ điều phối; quy tắc nằm ở
 * {@link ProcessContractLifecycleUseCase} (idempotent, an toàn khi nhiều instance). Tắt bằng
 * {@code lms.contract.job.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "lms.contract.job.enabled", havingValue = "true", matchIfMissing = true)
public class ContractLifecycleJob {

    private static final Logger log = LoggerFactory.getLogger(ContractLifecycleJob.class);

    private final ProcessContractLifecycleUseCase useCase;

    public ContractLifecycleJob(ProcessContractLifecycleUseCase useCase) {
        this.useCase = useCase;
    }

    @Scheduled(cron = "${lms.contract.job.cron:0 7 * * * *}", zone = "${lms.contract.zone:Asia/Ho_Chi_Minh}")
    public void run() {
        try {
            ProcessContractLifecycleUseCase.Result result = useCase.runOnce();
            if (result.applied() + result.expired() + result.reminded() + result.failed() > 0) {
                log.info("Vòng đời hợp đồng: áp dụng={}, hết hạn={}, nhắc={}, lỗi={}", result.applied(),
                        result.expired(), result.reminded(), result.failed());
            }
        } catch (RuntimeException e) {
            log.error("Job vòng đời hợp đồng thất bại: {}", e.toString(), e);
        }
    }
}
