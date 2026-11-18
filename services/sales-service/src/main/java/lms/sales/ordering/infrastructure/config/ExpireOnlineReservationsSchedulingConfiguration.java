package lms.sales.ordering.infrastructure.config;

import lms.sales.ordering.application.ExpireOnlineReservationsUseCase;
import lms.sales.shared.application.TenantDirectoryPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Bật job hết hạn giữ hàng đơn online khi {@code lms.sales.abuse.reservation-expiry-job-enabled} (mặc định true; profile
 * demo-seed tắt vì tiến trình một lần không có lịch).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "lms.sales.abuse.reservation-expiry-job-enabled", havingValue = "true",
        matchIfMissing = true)
public class ExpireOnlineReservationsSchedulingConfiguration {

    @Bean
    ExpireOnlineReservationsJob expireOnlineReservationsJob(TenantDirectoryPort tenants,
                                                           ExpireOnlineReservationsUseCase useCase) {
        return new ExpireOnlineReservationsJob(tenants, useCase);
    }
}
