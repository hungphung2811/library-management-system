package lms.identity.department.application;

import lms.identity.department.domain.Department;
import lms.identity.department.domain.DepartmentHierarchy;
import lms.identity.department.domain.DepartmentNotFoundException;
import lms.identity.department.domain.DepartmentRepository;
import lms.identity.department.domain.InvalidDepartmentException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Đổi tên và/hoặc chuyển phòng ban sang cha khác. Cha mới phải tồn tại, ACTIVE và không nằm trong nhánh của chính nó. */
public class UpdateDepartmentUseCase {

    private final DepartmentRepository repository;

    public UpdateDepartmentUseCase(DepartmentRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public DepartmentResponse execute(UUID id, UpdateDepartmentRequest request) {
        Department department = repository.findById(id).orElseThrow(() -> new DepartmentNotFoundException(id));
        department.rename(request.name());

        UUID newParent = request.parentId();
        if (!Objects.equals(department.parentId(), newParent)) {
            if (newParent != null) {
                Department parent = repository.findById(newParent).orElseThrow(() -> new DepartmentNotFoundException(newParent));
                if (!parent.isActive() && department.isActive()) {
                    throw new InvalidDepartmentException("Không thể chuyển phòng ban đang hoạt động vào phòng ban đã ngừng hoạt động");
                }
                if (new DepartmentHierarchy(repository.findAll()).isSelfOrDescendant(newParent, id)) {
                    throw new InvalidDepartmentException("Không thể chuyển phòng ban vào chính nó hoặc phòng ban con của nó");
                }
            }
            department.moveTo(newParent);
        }
        return DepartmentResponse.from(repository.save(department));
    }
}
