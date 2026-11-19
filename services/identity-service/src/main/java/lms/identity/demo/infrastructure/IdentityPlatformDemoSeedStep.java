package lms.identity.demo.infrastructure;

import lms.demo.runner.DemoSeedContext;
import lms.demo.runner.DemoSeedStep;
import lms.identity.demo.application.SeedDemoPlatformUseCase;

/**
 * Bước seed phần nền tảng của identity (ADR-0018): thanh toán hợp đồng, storefront origins, Application của Developer
 * Portal. Chạy sau {@link IdentityDemoSeedStep} (cần tenant và hợp đồng demo).
 */
public class IdentityPlatformDemoSeedStep implements DemoSeedStep {

    private final SeedDemoPlatformUseCase useCase;

    public IdentityPlatformDemoSeedStep(SeedDemoPlatformUseCase useCase) {
        this.useCase = useCase;
    }

    @Override
    public int order() {
        return 2;
    }

    @Override
    public String name() {
        return "identity.platform";
    }

    @Override
    public SeedResult run(DemoSeedContext context) {
        SeedDemoPlatformUseCase.Result result = useCase.execute(context.plan());
        return SeedResult.of(result.created(), result.skipped());
    }
}
