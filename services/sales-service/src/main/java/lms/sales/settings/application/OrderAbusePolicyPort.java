package lms.sales.settings.application;

import java.util.Optional;
import java.util.UUID;
import lms.sales.settings.domain.OrderAbusePolicy;

/** Lưu chính sách theo tenant; cùng quy ước khóa lạc quan với {@link SalesSettingsPort} (version 0 = chưa lưu). */
public interface OrderAbusePolicyPort {

    Optional<OrderAbusePolicy> find(UUID tenantId);

    OrderAbusePolicy save(OrderAbusePolicy policy);
}
