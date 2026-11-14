package lms.identity.department.application;

import lms.identity.department.domain.Department;
import lms.identity.department.domain.DepartmentRepository;
import lms.identity.department.domain.DepartmentStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ListDepartmentsUseCase {

    private final DepartmentRepository repository;

    public ListDepartmentsUseCase(DepartmentRepository repository) {
        this.repository = repository;
    }

    /** Danh sách phẳng, lọc theo trạng thái nếu có. */
    public List<DepartmentResponse> list(DepartmentStatus status) {
        return repository.findAll().stream()
                .filter(d -> status == null || d.status() == status)
                .map(DepartmentResponse::from).toList();
    }

    /**
     * Cây phòng ban, các nút cùng cấp sắp theo mã. Mặc định chỉ lấy phòng ban đang hoạt động; vì ACTIVE kéo theo cha ACTIVE
     * nên lọc không làm mất liên kết cha-con.
     */
    public List<DepartmentNode> tree(boolean includeInactive) {
        List<Department> all = repository.findAll().stream()
                .filter(d -> includeInactive || d.isActive())
                .sorted(Comparator.comparing(Department::code)).toList();
        Map<UUID, List<Department>> childrenOf = new HashMap<>();
        List<Department> roots = new ArrayList<>();
        Map<UUID, Department> present = new HashMap<>();
        all.forEach(d -> present.put(d.id(), d));
        for (Department d : all) {
            if (d.parentId() == null || !present.containsKey(d.parentId())) {
                roots.add(d);
            } else {
                childrenOf.computeIfAbsent(d.parentId(), k -> new ArrayList<>()).add(d);
            }
        }
        return roots.stream().map(r -> node(r, childrenOf)).toList();
    }

    private static DepartmentNode node(Department d, Map<UUID, List<Department>> childrenOf) {
        List<DepartmentNode> children = childrenOf.getOrDefault(d.id(), List.of()).stream()
                .map(c -> node(c, childrenOf)).toList();
        return new DepartmentNode(d.id(), d.code(), d.name(), d.status(), children);
    }
}
