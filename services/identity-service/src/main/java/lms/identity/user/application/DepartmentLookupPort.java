package lms.identity.user.application;

import java.util.UUID;

/** Cổng sang context department: phòng ban có tồn tại và đang hoạt động không. */
public interface DepartmentLookupPort {
    boolean existsActive(UUID departmentId);

    /** Phòng ban đang hoạt động của tenant hiện tại theo mã (chữ hoa) -> id; tra một lần cho cả lô nhập. */
    default java.util.Map<String, UUID> activeIdsByCode() {
        return java.util.Map.of();
    }

    /** id -> mã của mọi phòng ban của tenant hiện tại (xuất danh sách). */
    default java.util.Map<UUID, String> codesById() {
        return java.util.Map.of();
    }
}
