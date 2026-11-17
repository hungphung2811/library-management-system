package lms.sales.settings.application;

import java.util.Optional;
import java.util.UUID;
import lms.sales.settings.domain.SalesSettings;

/**
 * Lưu cấu hình theo tenant. {@code save}: version 0 thì chèn (lần lưu đầu), ngược lại cập nhật với điều kiện
 * version khớp (khóa lạc quan) rồi tăng version; trả bản đã lưu. Lệch version: adapter ném
 * SalesException(SETTINGS_VERSION_CONFLICT).
 */
public interface SalesSettingsPort {

    Optional<SalesSettings> find(UUID tenantId);

    SalesSettings save(SalesSettings settings);
}
