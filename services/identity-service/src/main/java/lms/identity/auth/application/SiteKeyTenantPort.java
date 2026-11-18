package lms.identity.auth.application;

import java.util.Optional;
import java.util.UUID;
import lms.identity.tenant.application.StorefrontTenant;

/** Tra tenant + Application theo site key công khai, do context apiclient đảm nhiệm (pha P2). Rỗng = không khả dụng. */
public interface SiteKeyTenantPort {

    Optional<SiteKeyTenant> resolve(String siteKey);

    /** allowedOrigins của tenant chỉ gồm origin của đúng Application. */
    record SiteKeyTenant(UUID applicationId, StorefrontTenant tenant) {
    }
}
