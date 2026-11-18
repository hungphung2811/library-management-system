package lms.identity.auth.infrastructure.client;

import java.util.Optional;
import lms.identity.apiclient.application.ResolveSiteKeyUseCase;
import lms.identity.auth.application.SiteKeyTenantPort;

/** Gọi tầng application của context apiclient trong cùng service. */
public class InProcessSiteKeyTenantAdapter implements SiteKeyTenantPort {

    private final ResolveSiteKeyUseCase resolve;

    public InProcessSiteKeyTenantAdapter(ResolveSiteKeyUseCase resolve) {
        this.resolve = resolve;
    }

    @Override
    public Optional<SiteKeyTenant> resolve(String siteKey) {
        return resolve.resolve(siteKey).map(r -> new SiteKeyTenant(r.applicationId(), r.tenant()));
    }
}
