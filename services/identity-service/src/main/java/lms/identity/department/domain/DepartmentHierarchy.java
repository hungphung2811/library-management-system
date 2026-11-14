package lms.identity.department.domain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Domain service: truy vấn trên toàn bộ cây phòng ban (kiểm tra chu trình, con đang hoạt động). */
public class DepartmentHierarchy {

    private final Map<UUID, Department> byId = new HashMap<>();

    public DepartmentHierarchy(List<Department> all) {
        all.forEach(d -> byId.put(d.id(), d));
    }

    /** True nếu candidate chính là id hoặc nằm dưới id (đi ngược lên theo cha gặp id). */
    public boolean isSelfOrDescendant(UUID candidate, UUID id) {
        UUID current = candidate;
        int guard = byId.size() + 1;
        while (current != null && guard-- > 0) {
            if (current.equals(id)) return true;
            Department d = byId.get(current);
            current = d == null ? null : d.parentId();
        }
        return false;
    }

    public boolean hasActiveChildren(UUID id) {
        return byId.values().stream().anyMatch(d -> id.equals(d.parentId()) && d.isActive());
    }
}
