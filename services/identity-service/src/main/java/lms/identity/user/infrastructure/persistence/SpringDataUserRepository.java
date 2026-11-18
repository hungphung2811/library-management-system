package lms.identity.user.infrastructure.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataUserRepository extends JpaRepository<UserJpaEntity, UUID>, JpaSpecificationExecutor<UserJpaEntity> {

    boolean existsByUsername(String username);

    @Query("select u.username from UserJpaEntity u where u.username in :usernames")
    java.util.List<String> findUsernamesIn(@Param("usernames") java.util.Collection<String> usernames);

    @Query("select lower(u.email) from UserJpaEntity u where lower(u.email) in :emails")
    java.util.List<String> findEmailsIn(@Param("emails") java.util.Collection<String> emails);

    @Query("select count(u) > 0 from UserJpaEntity u where lower(u.email) = lower(:email) and (:excludeId is null or u.id <> :excludeId)")
    boolean existsByEmailExcluding(@Param("email") String email, @Param("excludeId") UUID excludeId);
}
