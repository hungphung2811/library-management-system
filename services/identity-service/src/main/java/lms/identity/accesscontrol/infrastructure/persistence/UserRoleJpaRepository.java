package lms.identity.accesscontrol.infrastructure.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRoleJpaRepository extends JpaRepository<UserRoleJpaEntity, UserRoleJpaEntity.Key> {

    List<UserRoleJpaEntity> findByUserId(UUID userId);
}
