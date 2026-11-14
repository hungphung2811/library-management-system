package lms.identity.department.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DepartmentRepository {

    Department save(Department department);

    Optional<Department> findById(UUID id);

    /** Toàn bộ phòng ban, sắp theo mã. Số lượng nhỏ nên nạp hết để tính trên cây. */
    List<Department> findAll();

    boolean existsByCode(String code);
}
