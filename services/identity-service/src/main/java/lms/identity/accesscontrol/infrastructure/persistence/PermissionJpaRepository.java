package lms.identity.accesscontrol.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PermissionJpaRepository extends JpaRepository<PermissionJpaEntity, String> {
}
