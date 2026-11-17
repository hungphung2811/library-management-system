package lms.sales.settings.application;

import java.time.Clock;
import java.util.Map;
import lms.sales.settings.domain.SalesSettings;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import org.springframework.transaction.annotation.Transactional;

public class UpdateSalesSettingsUseCase {

    private final SalesSettingsPort port;
    private final SalesAuditPort audit;
    private final Clock clock;

    public UpdateSalesSettingsUseCase(SalesSettingsPort port, SalesAuditPort audit, Clock clock) {
        this.port = port;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public SalesSettingsView execute(UpdateSalesSettingsCommand cmd) {
        SalesSettings current = port.find(cmd.tenantId()).orElseGet(() -> SalesSettings.defaults(cmd.tenantId()));
        if (current.version() != cmd.expectedVersion()) {
            throw new SalesException(ErrorCode.SETTINGS_VERSION_CONFLICT, "Cấu hình đã được người khác thay đổi");
        }
        var now = clock.instant();
        SalesSettings updated = current.update(cmd.vatRateBps(), cmd.pricesIncludeVat(), cmd.returnWindowDays(),
                cmd.fixedShippingFee(), cmd.lowStockThreshold(), cmd.walkInPiiRetentionMonths(), now, cmd.userId());
        SalesSettings saved = port.save(updated);
        audit.record(new SalesAuditPort.AuditEntry(cmd.tenantId(), SalesAuditPort.Actions.SETTINGS_CHANGED,
                "SalesSettings", cmd.tenantId(), cmd.userId(), now,
                Map.of("vatRateBps", saved.vatRateBps(), "pricesIncludeVat", saved.pricesIncludeVat(),
                        "returnWindowDays", saved.returnWindowDays(), "fixedShippingFee", saved.fixedShippingFee(),
                        "lowStockThreshold", saved.lowStockThreshold(),
                        "walkInPiiRetentionMonths", saved.walkInPiiRetentionMonths())));
        return SalesSettingsView.from(saved);
    }
}
