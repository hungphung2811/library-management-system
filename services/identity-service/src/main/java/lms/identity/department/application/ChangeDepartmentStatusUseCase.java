package lms.identity.department.application;

import lms.identity.department.domain.Department;
import lms.identity.department.domain.DepartmentHierarchy;
import lms.identity.department.domain.DepartmentNotFoundException;
import lms.identity.department.domain.DepartmentRepository;
import lms.identity.department.domain.DepartmentStatus;
import lms.identity.department.domain.InvalidDepartmentException;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ngừng/kích hoạt lại. Giữ bất biến "ACTIVE thì cha ACTIVE": chỉ ngừng được khi không còn phòng ban con đang hoạt động,
 * chỉ kích hoạt lại được khi cha đang hoạt động. Người dùng thuộc phòng ban đã ngừng vẫn giữ nguyên phòng ban.
 */
public class ChangeDepartmentStatusUseCase {

    private final DepartmentRepository repository;

    public ChangeDepartmentStatusUseCase(DepartmentRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public DepartmentResponse execute(UUID id, DepartmentStatus target) {
        Department department = repository.findById(id).orElseThrow(() -> new DepartmentNotFoundException(id));
        if (target == DepartmentStatus.INACTIVE) {
            if (new DepartmentHierarchy(repository.findAll()).hasActiveChildren(id)) {
                throw new InvalidDepartmentException("Còn phòng ban con đang hoạt động, hãy ngừng các phòng ban con trước");
            }
            department.deactivate();
        } else {
            if (department.parentId() != null) {
                Department parent = repository.findById(department.parentId())
                        .orElseThrow(() -> new DepartmentNotFoundException(department.parentId()));
                if (!parent.isActive()) {
                    throw new InvalidDepartmentException("Phòng ban cha đang ngừng hoạt động, hãy kích hoạt phòng ban cha trước");
                }
            }
            department.activate();
        }
        return DepartmentResponse.from(repository.save(department));
    }
}
