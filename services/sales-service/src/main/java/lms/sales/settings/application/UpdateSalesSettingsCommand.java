package lms.sales.settings.application;

import java.util.UUID;

/** {@code expectedVersion} = version client đã đọc (0 nếu chưa từng lưu). */
public record UpdateSalesSettingsCommand(UUID tenantId, UUID userId, int vatRateBps, boolean pricesIncludeVat,
                                         int returnWindowDays, long fixedShippingFee, int lowStockThreshold,
                                         int walkInPiiRetentionMonths, long expectedVersion) {
}
