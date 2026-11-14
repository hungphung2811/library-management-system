package lms.identity.department.infrastructure.persistence;

import lms.identity.department.domain.Department;
import lms.identity.department.domain.DepartmentRepository;
import lms.identity.department.domain.DuplicateDepartmentException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
class DepartmentPersistenceAdapter implements DepartmentRepository {

    private final SpringDataDepartmentRepository jpa;

    DepartmentPersistenceAdapter(SpringDataDepartmentRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Department save(Department d) {
        try {
            jpa.saveAndFlush(new DepartmentJpaEntity(d.id(), d.code(), d.name(), d.parentId(), d.status()));
        } catch (DataIntegrityViolationException e) {
            // unique(code): hai yêu cầu tạo cùng mã đồng thời
            throw new DuplicateDepartmentException("Mã phòng ban đã tồn tại: " + d.code());
        }
        return d;
    }

    @Override
    public Optional<Department> findById(UUID id) {
        return jpa.findById(id).map(DepartmentPersistenceAdapter::toDomain);
    }

    @Override
    public List<Department> findAll() {
        return jpa.findAllByOrderByCode().stream().map(DepartmentPersistenceAdapter::toDomain).toList();
    }

    @Override
    public boolean existsByCode(String code) {
        return jpa.existsByCode(code);
    }

    private static Department toDomain(DepartmentJpaEntity e) {
        return Department.restore(e.getId(), e.getCode(), e.getName(), e.getParentId(), e.getStatus());
    }
}
