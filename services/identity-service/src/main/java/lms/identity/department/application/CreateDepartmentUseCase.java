package lms.identity.department.application;

import lms.identity.department.domain.Department;
import lms.identity.department.domain.DepartmentNotFoundException;
import lms.identity.department.domain.DepartmentRepository;
import lms.identity.department.domain.DuplicateDepartmentException;
import lms.identity.department.domain.InvalidDepartmentException;
import org.springframework.transaction.annotation.Transactional;

public class CreateDepartmentUseCase {

    private final DepartmentRepository repository;

    public CreateDepartmentUseCase(DepartmentRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public DepartmentResponse execute(CreateDepartmentRequest request) {
        Department department = Department.create(request.code(), request.name(), request.parentId());
        if (repository.existsByCode(department.code())) {
            throw new DuplicateDepartmentException("Mã phòng ban đã tồn tại: " + department.code());
        }
        if (department.parentId() != null) {
            Department parent = repository.findById(department.parentId())
                    .orElseThrow(() -> new DepartmentNotFoundException(department.parentId()));
            if (!parent.isActive()) {
                throw new InvalidDepartmentException("Phòng ban cha đang ngừng hoạt động: " + parent.code());
            }
        }
        return DepartmentResponse.from(repository.save(department));
    }
}
