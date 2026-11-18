package lms.identity.apiclient.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataApplicationScopeRepository extends JpaRepository<ApplicationScopeJpaEntity, ApplicationScopeId> {

    @Query("select s from ApplicationScopeJpaEntity s where s.applicationId in :ids")
    List<ApplicationScopeJpaEntity> findByApplicationIds(@Param("ids") Collection<UUID> ids);
}
