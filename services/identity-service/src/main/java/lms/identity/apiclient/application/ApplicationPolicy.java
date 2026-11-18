package lms.identity.apiclient.application;

import java.time.Duration;

/**
 * Cấu hình nghiệp vụ Application (lms.identity.applications.*): số Application tối đa mỗi tenant, hạn dùng mặc định và
 * tối đa của credential (Q13: mặc định 365 ngày).
 */
public record ApplicationPolicy(int maxApplicationsPerTenant, Duration defaultCredentialTtl,
                                Duration maxCredentialTtl) {

    public static final int DEFAULT_MAX_APPLICATIONS = 20;
    public static final Duration DEFAULT_CREDENTIAL_TTL = Duration.ofDays(365);
    public static final Duration DEFAULT_MAX_CREDENTIAL_TTL = Duration.ofDays(730);

    public ApplicationPolicy {
        if (maxApplicationsPerTenant < 1) throw new IllegalArgumentException("max-per-tenant phải >= 1");
        if (defaultCredentialTtl.isNegative() || defaultCredentialTtl.isZero()) {
            throw new IllegalArgumentException("credential-ttl phải > 0");
        }
        if (maxCredentialTtl.compareTo(defaultCredentialTtl) < 0) {
            throw new IllegalArgumentException("credential-max-ttl phải >= credential-ttl");
        }
    }

    public static ApplicationPolicy defaults() {
        return new ApplicationPolicy(DEFAULT_MAX_APPLICATIONS, DEFAULT_CREDENTIAL_TTL, DEFAULT_MAX_CREDENTIAL_TTL);
    }
}
