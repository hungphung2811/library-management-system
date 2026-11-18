package lms.identity.auth.application;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Phát token dịch vụ (không phải người dùng): claim svc, tenantId, modules, permissions rỗng (ADR-0011). */
public interface ServiceTokenIssuerPort {

    TokenIssuerPort.IssuedToken issueService(ServiceClaims claims);

    /** applicationId null = token theo mã tenant ({code}); khác null = token theo site key, mang claim application_id. */
    record ServiceClaims(String service, UUID subject, UUID tenantId, List<String> modules, Duration ttl,
                         UUID applicationId) {

        public ServiceClaims {
            modules = List.copyOf(modules);
        }

        public ServiceClaims(String service, UUID subject, UUID tenantId, List<String> modules, Duration ttl) {
            this(service, subject, tenantId, modules, ttl, null);
        }
    }
}
