package lms.identity.department.infrastructure.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataDepartmentRepository extends JpaRepository<DepartmentJpaEntity, UUID> {

    boolean existsByCode(String code);

    List<DepartmentJpaEntity> findAllByOrderByCode();
}
