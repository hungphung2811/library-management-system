package lms.identity.auth.application;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import lms.identity.auth.application.ServiceTokenIssuerPort.ServiceClaims;
import lms.identity.tenant.application.StorefrontTenant;
import lms.security.JwtClaims;

/**
 * Cấp token dịch vụ storefront cho customer-api (ADR-0011 2.1): tenant tồn tại, ACTIVE và bật SALES hoặc LENDING (theo resolver) thì phát token
 * theo tenant, sống ngắn; ngược lại trả rỗng (một kết quả chung, không lộ lý do).
 */
public class IssueStorefrontTokenUseCase {

    /** sub dành riêng của token storefront; không trùng user nào (bootstrap admin dùng ...0001). */
    public static final UUID STOREFRONT_SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000005f01");

    private final StorefrontTenantPort tenants;
    private final ServiceTokenIssuerPort issuer;
    private final Duration ttl;

    public IssueStorefrontTokenUseCase(StorefrontTenantPort tenants, ServiceTokenIssuerPort issuer, Duration ttl) {
        this.tenants = tenants;
        this.issuer = issuer;
        this.ttl = ttl;
    }

    public Optional<StorefrontAccess> execute(String tenantCode) {
        return tenants.resolve(tenantCode).map(t -> {
            TokenIssuerPort.IssuedToken token = issuer.issueService(new ServiceClaims(
                    JwtClaims.SERVICE_CUSTOMER_API, STOREFRONT_SUBJECT, t.id(), t.modules(), ttl));
            return new StorefrontAccess(t, token.value(), token.expiresInSeconds());
        });
    }

    public record StorefrontAccess(StorefrontTenant tenant, String accessToken, long expiresIn) {
    }
}
