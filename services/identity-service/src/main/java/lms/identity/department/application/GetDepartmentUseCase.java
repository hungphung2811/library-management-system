package lms.identity.department.application;

import lms.identity.department.domain.DepartmentNotFoundException;
import lms.identity.department.domain.DepartmentRepository;
import java.util.Optional;
import java.util.UUID;

/** Cũng là API của context department cho context khác (user kiểm tra phòng ban khi gán cho người dùng). */
public class GetDepartmentUseCase {

    private final DepartmentRepository repository;

    public GetDepartmentUseCase(DepartmentRepository repository) {
        this.repository = repository;
    }

    public DepartmentResponse execute(UUID id) {
        return find(id).orElseThrow(() -> new DepartmentNotFoundException(id));
    }

    /** Không ném lỗi: dành cho context khác không muốn phụ thuộc exception của department. */
    public Optional<DepartmentResponse> find(UUID id) {
        return repository.findById(id).map(DepartmentResponse::from);
    }
}
