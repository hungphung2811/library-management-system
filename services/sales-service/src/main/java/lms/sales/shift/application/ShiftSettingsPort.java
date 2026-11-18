package lms.sales.shift.application;

import java.util.Optional;
import java.util.UUID;
import lms.sales.shift.domain.ShiftSettings;

/** Lưu cấu hình ca theo tenant; cùng quy ước khóa lạc quan với chính sách khác (version 0 = chưa lưu). */
public interface ShiftSettingsPort {

    Optional<ShiftSettings> find(UUID tenantId);

    /** Lưu theo version: lệch hoặc trùng khóa thì 409 SETTINGS_VERSION_CONFLICT. */
    ShiftSettings save(ShiftSettings settings);
}
