package lms.identity.user.infrastructure.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataUserRepository extends JpaRepository<UserJpaEntity, UUID>, JpaSpecificationExecutor<UserJpaEntity> {

    boolean existsByUsername(String username);

    @Query("select count(u) > 0 from UserJpaEntity u where lower(u.email) = lower(:email) and (:excludeId is null or u.id <> :excludeId)")
    boolean existsByEmailExcluding(@Param("email") String email, @Param("excludeId") UUID excludeId);
}
