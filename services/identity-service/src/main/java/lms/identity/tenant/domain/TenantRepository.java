package lms.identity.tenant.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantRepository {

    /** @throws DuplicateTenantException nếu mã đã tồn tại */
    Tenant save(Tenant tenant);

    Optional<Tenant> findById(UUID id);

    /** Nạp theo lô (một câu lệnh); id không tồn tại bị bỏ qua, không bảo đảm thứ tự. */
    default List<Tenant> findAllByIds(java.util.Collection<UUID> ids) {
        return ids.stream().distinct().map(this::findById).flatMap(Optional::stream).toList();
    }

    /**
     * Đọc để sửa: adapter thật khóa dòng (SELECT ... FOR UPDATE) cho tới hết transaction hiện tại, nên phải gọi
     * trong transaction; tránh hai thao tác đồng thời ghi đè lẫn nhau (ví dụ đổi tên làm status SUSPENDED về ACTIVE).
     */
    default Optional<Tenant> findByIdForUpdate(UUID id) {
        return findById(id);
    }

    Optional<Tenant> findByCode(String code);

    List<Tenant> findAll();
}
