package lms.identity.auth.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ExternalLoginJpaRepository extends JpaRepository<ExternalLoginJpaEntity, UUID> {

    Optional<ExternalLoginJpaEntity> findByProviderAndSubject(String provider, String subject);

    Optional<ExternalLoginJpaEntity> findByUserIdAndProvider(UUID userId, String provider);

    List<ExternalLoginJpaEntity> findByUserIdOrderByLinkedAt(UUID userId);
}
