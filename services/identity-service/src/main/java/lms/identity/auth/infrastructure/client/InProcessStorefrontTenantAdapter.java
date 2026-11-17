package lms.identity.auth.infrastructure.client;

import java.util.Optional;
import lms.identity.auth.application.StorefrontTenantPort;
import lms.identity.tenant.application.ResolveStorefrontTenantUseCase;
import lms.identity.tenant.application.StorefrontTenant;

/** Gọi tầng application của context tenant trong cùng service. */
public class InProcessStorefrontTenantAdapter implements StorefrontTenantPort {

    private final ResolveStorefrontTenantUseCase resolve;

    public InProcessStorefrontTenantAdapter(ResolveStorefrontTenantUseCase resolve) {
        this.resolve = resolve;
    }

    @Override
    public Optional<StorefrontTenant> resolve(String tenantCode) {
        return resolve.execute(tenantCode);
    }
}
