package lms.identity.tenant.infrastructure.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataStorefrontOriginHistoryRepository
        extends JpaRepository<TenantStorefrontOriginHistoryJpaEntity, UUID> {
}
