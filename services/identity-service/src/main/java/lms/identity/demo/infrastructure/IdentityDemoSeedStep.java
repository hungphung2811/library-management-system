package lms.identity.demo.infrastructure;

import lms.demo.runner.DemoSeedContext;
import lms.demo.runner.DemoSeedStep;
import lms.identity.demo.application.SeedDemoIdentityUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bước seed của identity (ADR-0018), chạy đầu tiên vì tạo tenant demo. Identity tự tạo tenant nên
 * {@code lms.demo-seed.creates-tenant=true}, {@link DemoSeedContext#tenantId()} là null và bước tự đặt TenantContext.
 */
public class IdentityDemoSeedStep implements DemoSeedStep {

    private static final Logger log = LoggerFactory.getLogger(IdentityDemoSeedStep.class);

    private final SeedDemoIdentityUseCase useCase;

    public IdentityDemoSeedStep(SeedDemoIdentityUseCase useCase) {
        this.useCase = useCase;
    }

    @Override
    public int order() {
        return 1;
    }

    @Override
    public String name() {
        return "identity";
    }

    @Override
    public SeedResult run(DemoSeedContext context) {
        SeedDemoIdentityUseCase.Result result = useCase.execute(context.plan(), context.password());
        log.info("DEMO_TENANT_ID={}", lms.demo.plan.DemoIds.tenant());
        return SeedResult.of(result.created(), result.skipped());
    }
}
