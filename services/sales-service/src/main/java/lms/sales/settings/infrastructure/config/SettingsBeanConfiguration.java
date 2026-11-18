package lms.sales.settings.infrastructure.config;

import java.time.Clock;
import lms.sales.settings.application.GetSalesSettingsUseCase;
import lms.sales.settings.application.OrderAbusePolicyPort;
import lms.sales.settings.application.OrderAbusePolicyUseCases;
import lms.sales.settings.application.SalesSettingsPort;
import lms.sales.settings.application.UpdateSalesSettingsUseCase;
import lms.sales.settings.domain.OrderAbusePolicy;
import lms.sales.shared.application.SalesAuditPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root của context settings. */
@Configuration
public class SettingsBeanConfiguration {

    @Bean
    GetSalesSettingsUseCase getSalesSettingsUseCase(SalesSettingsPort port) {
        return new GetSalesSettingsUseCase(port);
    }

    @Bean
    UpdateSalesSettingsUseCase updateSalesSettingsUseCase(SalesSettingsPort port, SalesAuditPort audit, Clock clock) {
        return new UpdateSalesSettingsUseCase(port, audit, clock);
    }

    /**
     * Chính sách chống lạm dụng đặt hàng (docs/security/ORDER_ABUSE_AUDIT.md). Giá trị mặc định cho tenant chưa cấu hình
     * lấy từ {@code lms.sales.abuse.defaults.*} (đổi bằng biến môi trường hoặc application.yml, không hardcode).
     */
    @Bean
    OrderAbusePolicyUseCases orderAbusePolicyUseCases(
            OrderAbusePolicyPort port, SalesAuditPort audit, Clock clock,
            @Value("${lms.sales.abuse.defaults.reservation-minutes:15}") int reservationMinutes,
            @Value("${lms.sales.abuse.defaults.max-pending-orders:5}") int maxPendingOrders,
            @Value("${lms.sales.abuse.defaults.max-orders-per-day:20}") int maxOrdersPerDay,
            @Value("${lms.sales.abuse.defaults.max-quantity-per-line:50}") int maxQuantityPerLine,
            @Value("${lms.sales.abuse.defaults.max-total-quantity:100}") int maxTotalQuantity,
            @Value("${lms.sales.abuse.defaults.orders-per-minute-per-user:5}") int perUser,
            @Value("${lms.sales.abuse.defaults.orders-per-minute-tenant:300}") int perTenant) {
        return new OrderAbusePolicyUseCases(port, new OrderAbusePolicy.Values(reservationMinutes, maxPendingOrders,
                maxOrdersPerDay, maxQuantityPerLine, maxTotalQuantity, perUser, perTenant), audit, clock);
    }
}
