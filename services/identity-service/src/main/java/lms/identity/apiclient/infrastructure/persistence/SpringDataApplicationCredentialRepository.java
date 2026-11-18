package lms.identity.apiclient.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataApplicationCredentialRepository extends JpaRepository<ApplicationCredentialJpaEntity, UUID> {

    @Query("select c from ApplicationCredentialJpaEntity c where c.applicationId = :applicationId "
            + "order by c.createdAt, c.id")
    List<ApplicationCredentialJpaEntity> findByApplication(@Param("applicationId") UUID applicationId);

    @Query("select c from ApplicationCredentialJpaEntity c where c.applicationId in :ids order by c.createdAt, c.id")
    List<ApplicationCredentialJpaEntity> findByApplications(@Param("ids") Collection<UUID> ids);

    @Query("select c from ApplicationCredentialJpaEntity c where c.id = :id and c.applicationId = :applicationId")
    Optional<ApplicationCredentialJpaEntity> findByIdAndApplication(@Param("id") UUID id,
                                                                    @Param("applicationId") UUID applicationId);
}
