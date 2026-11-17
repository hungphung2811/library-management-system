package lms.identity.auth.application;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Phát token dịch vụ (không phải người dùng): claim svc, tenantId, modules, permissions rỗng (ADR-0011). */
public interface ServiceTokenIssuerPort {

    TokenIssuerPort.IssuedToken issueService(ServiceClaims claims);

    record ServiceClaims(String service, UUID subject, UUID tenantId, List<String> modules, Duration ttl) {

        public ServiceClaims {
            modules = List.copyOf(modules);
        }
    }
}
