package lms.identity.tenant.application;

import java.time.Instant;
import java.util.UUID;
import lms.identity.tenant.domain.Organization;

public record OrganizationResponse(UUID id, String name, Instant createdAt) {

    public static OrganizationResponse from(Organization o) {
        return new OrganizationResponse(o.id(), o.name(), o.createdAt());
    }
}
