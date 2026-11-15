package lms.system.menu.application;

import java.util.List;
import java.util.UUID;
import lms.system.menu.domain.MenuItem;

public interface MenuRepositoryPort {

    /** Mọi mục của tenant (kể cả đang tắt), không bảo đảm thứ tự. */
    List<MenuItem> findAll(UUID tenantId);

    /** Thêm mới hoặc cập nhật; trả bản đã lưu (version do tầng lưu trữ quản lý). */
    MenuItem save(MenuItem item);

    void deleteById(UUID tenantId, UUID id);
}
