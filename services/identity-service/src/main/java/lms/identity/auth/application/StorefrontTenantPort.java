package lms.identity.auth.application;

import java.util.Optional;
import lms.identity.tenant.application.StorefrontTenant;

/** Tra tenant khả dụng cho storefront theo mã, do context tenant đảm nhiệm (ADR-0011). Rỗng = không khả dụng. */
public interface StorefrontTenantPort {

    Optional<StorefrontTenant> resolve(String tenantCode);
}
