package lms.sales.shift.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.shift.application.ShiftSettingsPort;
import lms.sales.shift.domain.ShiftSettings;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JDBC như chính sách chống lạm dụng (khóa chính là tenant_id, chịu RLS, khóa lạc quan theo version). */
@Repository
class ShiftSettingsPersistenceAdapter implements ShiftSettingsPort {

    private static final String COLUMNS =
            "tenant_id, require_open_shift_for_pos, variance_threshold, version, updated_at, updated_by";

    private final JdbcTemplate jdbc;

    ShiftSettingsPersistenceAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ShiftSettings> find(UUID tenantId) {
        return jdbc.query("select " + COLUMNS + " from cashier_shift_settings where tenant_id = ?", (rs, i) ->
                ShiftSettings.restore(rs.getObject("tenant_id", UUID.class),
                        rs.getBoolean("require_open_shift_for_pos"), rs.getLong("variance_threshold"),
                        rs.getLong("version"), rs.getTimestamp("updated_at").toInstant(),
                        rs.getObject("updated_by", UUID.class)), tenantId).stream().findFirst();
    }

    @Override
    @Transactional
    public ShiftSettings save(ShiftSettings s) {
        Timestamp at = Timestamp.from(s.updatedAt() == null ? Instant.now() : s.updatedAt());
        if (s.version() == 0) {
            try {
                jdbc.update("insert into cashier_shift_settings (" + COLUMNS + ") values (?, ?, ?, 1, ?, ?)",
                        s.tenantId(), s.requireOpenShiftForPos(), s.varianceThreshold(), at, s.updatedBy());
            } catch (DuplicateKeyException e) {
                throw conflict();
            }
        } else {
            int rows = jdbc.update("update cashier_shift_settings set require_open_shift_for_pos = ?, "
                            + "variance_threshold = ?, updated_at = ?, updated_by = ?, version = version + 1 "
                            + "where tenant_id = ? and version = ?",
                    s.requireOpenShiftForPos(), s.varianceThreshold(), at, s.updatedBy(), s.tenantId(), s.version());
            if (rows != 1) {
                throw conflict();
            }
        }
        return find(s.tenantId()).orElseThrow();
    }

    private static SalesException conflict() {
        return new SalesException(ErrorCode.SETTINGS_VERSION_CONFLICT, "Cấu hình ca đã được người khác thay đổi");
    }
}
