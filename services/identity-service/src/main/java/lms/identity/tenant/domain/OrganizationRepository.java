package lms.identity.tenant.domain;

import java.util.Optional;
import java.util.UUID;

public interface OrganizationRepository {

    Organization save(Organization organization);

    Optional<Organization> findById(UUID id);
}
