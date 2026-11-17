package lms.sales.settings.application;

import java.time.Instant;
import java.util.UUID;
import lms.sales.settings.domain.SalesSettings;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.Rate;

/** Cấu hình hiệu lực dành cho context khác và tầng web (context khác chỉ được dùng tầng application). */
public record SalesSettingsView(UUID tenantId, int vatRateBps, boolean pricesIncludeVat, int returnWindowDays,
                                long fixedShippingFee, int lowStockThreshold, int walkInPiiRetentionMonths,
                                long version, Instant updatedAt, UUID updatedBy, String currency) {

    public static SalesSettingsView from(SalesSettings s) {
        return new SalesSettingsView(s.tenantId(), s.vatRateBps(), s.pricesIncludeVat(), s.returnWindowDays(),
                s.fixedShippingFee(), s.lowStockThreshold(), s.walkInPiiRetentionMonths(), s.version(),
                s.updatedAt(), s.updatedBy(), s.currency());
    }

    public Rate vatRate() {
        return Rate.ofBps(vatRateBps);
    }

    public Money fixedShippingMoney() {
        return Money.of(fixedShippingFee);
    }
}
