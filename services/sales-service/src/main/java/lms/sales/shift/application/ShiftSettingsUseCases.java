package lms.sales.shift.application;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.shift.domain.ShiftSettings;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đọc và đổi cấu hình ca của tenant. Không cache: cờ chặn bán và ngưỡng phải có hiệu lực ngay, và use case nghiệp vụ
 * đọc trực tiếp qua {@link ShiftSettingsPort}.
 */
public class ShiftSettingsUseCases {

    private final ShiftSettingsPort port;
    private final SalesAuditPort audit;
    private final Clock clock;

    public ShiftSettingsUseCases(ShiftSettingsPort port, SalesAuditPort audit, Clock clock) {
        this.port = port;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ShiftSettings get(UUID tenantId) {
        return port.find(tenantId).orElseGet(() -> ShiftSettings.defaults(tenantId));
    }

    @Transactional
    public ShiftSettings update(UUID tenantId, UUID actor, boolean requireOpenShiftForPos, long varianceThreshold,
                                long expectedVersion) {
        ShiftSettings current = port.find(tenantId).orElseGet(() -> ShiftSettings.defaults(tenantId));
        if (current.version() != expectedVersion) {
            throw new SalesException(ErrorCode.SETTINGS_VERSION_CONFLICT, "Cấu hình ca đã được người khác thay đổi");
        }
        var now = clock.instant();
        ShiftSettings saved = port.save(current.update(requireOpenShiftForPos, varianceThreshold, now, actor));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requireOpenShiftForPos", saved.requireOpenShiftForPos());
        data.put("varianceThreshold", saved.varianceThreshold());
        audit.record(new SalesAuditPort.AuditEntry(tenantId, SalesAuditPort.Actions.SHIFT_SETTINGS_CHANGED,
                "ShiftSettings", tenantId, actor, now, data));
        return saved;
    }
}
