package lms.circulation.tenant.domain;

/** Trạng thái tenant mà circulation biết qua event của identity (known_tenants.status). */
public enum KnownTenantStatus {
    ACTIVE,
    SUSPENDED
}
