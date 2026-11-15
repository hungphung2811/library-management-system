package lms.identity.tenant.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import lms.identity.tenant.domain.Organization;
import lms.identity.tenant.domain.OrganizationRepository;
import org.springframework.stereotype.Repository;

@Repository
class OrganizationPersistenceAdapter implements OrganizationRepository {

    private final SpringDataOrganizationRepository jpa;

    OrganizationPersistenceAdapter(SpringDataOrganizationRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Organization save(Organization o) {
        jpa.saveAndFlush(new OrganizationJpaEntity(o.id(), o.name(), o.createdAt()));
        return o;
    }

    @Override
    public Optional<Organization> findById(UUID id) {
        return jpa.findById(id).map(e -> Organization.restore(e.getId(), e.getName(), e.getCreatedAt()));
    }
}
