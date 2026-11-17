package lms.system.cms.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

interface SpringDataContactRequestRepository
        extends JpaRepository<ContactRequestJpaEntity, UUID>, JpaSpecificationExecutor<ContactRequestJpaEntity> {

    List<ContactRequestJpaEntity> findByAnonymizedAtIsNullAndCreatedAtBeforeOrderByCreatedAtAsc(Instant cutoff,
                                                                                                Pageable pageable);
}
