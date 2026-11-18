package lms.sales.ordering.infrastructure.config;

import lms.sales.ordering.application.ExpirePaymentIntentsUseCase;
import lms.sales.shared.application.TenantDirectoryPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Bật job dọn lệnh thanh toán hết hạn (mọi phương thức, ADR-0021) khi {@code app.payments.expire-job-enabled} (mặc định
 * true; profile demo-seed tắt để tiến trình một lần không có lịch).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.payments.expire-job-enabled", havingValue = "true", matchIfMissing = true)
public class ExpirePaymentIntentsSchedulingConfiguration {

    @Bean
    ExpirePaymentIntentsJob expirePaymentIntentsJob(TenantDirectoryPort tenants, ExpirePaymentIntentsUseCase useCase) {
        return new ExpirePaymentIntentsJob(tenants, useCase);
    }
}
