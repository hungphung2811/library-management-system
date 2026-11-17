package lms.sales.settings.infrastructure.demo;

import java.util.UUID;
import lms.demo.plan.DemoPlan;
import lms.demo.runner.DemoSeedContext;
import lms.demo.runner.DemoSeedStep;
import lms.sales.settings.application.SalesSettingsPort;
import lms.sales.settings.domain.SalesSettings;
import lms.sales.shared.infrastructure.demo.DemoTimes;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Seed cấu hình bán hàng của tenant demo (ADR-0018): VAT 10% giá chưa gồm thuế (khớp công thức của kế hoạch), phí ship
 * cố định 30.000, ngưỡng tồn thấp chung {@link DemoPlan#GENERAL_LOW_STOCK_THRESHOLD}. Đã có cấu hình thì bỏ qua
 * (không ghi đè cấu hình người dùng đã sửa).
 */
@Component
@Profile("demo-seed")
public class SettingsDemoSeedStep implements DemoSeedStep {

    private final SalesSettingsPort settings;
    private final TransactionOperations tx;

    public SettingsDemoSeedStep(SalesSettingsPort settings, TransactionOperations tx) {
        this.settings = settings;
        this.tx = tx;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public String name() {
        return "sales.settings";
    }

    @Override
    public SeedResult run(DemoSeedContext context) {
        UUID tenantId = requireTenant(context);
        return tx.execute(status -> {
            if (settings.find(tenantId).isPresent()) {
                return SeedResult.of(0, 1);
            }
            SalesSettings demo = SalesSettings.defaults(tenantId).update(DemoTimes.VAT_RATE_BPS,
                    DemoTimes.PRICES_INCLUDE_VAT, DemoTimes.RETURN_WINDOW_DAYS, DemoTimes.FIXED_SHIPPING_FEE,
                    DemoPlan.GENERAL_LOW_STOCK_THRESHOLD, DemoTimes.WALK_IN_RETENTION_MONTHS,
                    context.clock().instant(), null);
            settings.save(demo);
            return SeedResult.of(1, 0);
        });
    }

    private static UUID requireTenant(DemoSeedContext context) {
        if (context.tenantId() == null) {
            throw new IllegalStateException("Seed bán hàng cần lms.demo-seed.tenant-id");
        }
        return context.tenantId();
    }
}
