package lms.sales.invoicing.application;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import lms.sales.invoicing.domain.InvoiceSettings;
import lms.sales.shared.application.SalesAuditPort;
import org.springframework.transaction.annotation.Transactional;

/** Cấu hình hóa đơn theo tenant (người bán, mẫu số, ký hiệu, tự phát hành). Đổi cấu hình không đổi hóa đơn đã phát hành. */
public class InvoiceSettingsUseCases {

    public record UpdateCommand(UUID tenantId, UUID actor, String sellerLegalName, String sellerTaxCode,
                                String sellerAddress, String sellerPhone, String sellerEmail, String templateCode,
                                String series, boolean autoIssueOnCompletion, long version) {
    }

    private final InvoiceSettingsPort port;
    private final SalesAuditPort audit;
    private final Clock clock;

    public InvoiceSettingsUseCases(InvoiceSettingsPort port, SalesAuditPort audit, Clock clock) {
        this.port = port;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public InvoiceSettingsView get(UUID tenantId) {
        return InvoiceSettingsView.from(port.find(tenantId).orElse(InvoiceSettings.defaults(tenantId)));
    }

    /** {@code version} phải bằng version đã đọc (0 nếu chưa từng lưu); lệch thì 409 SETTINGS_VERSION_CONFLICT. */
    @Transactional
    public InvoiceSettingsView update(UpdateCommand cmd) {
        InvoiceSettings current = port.find(cmd.tenantId()).orElse(InvoiceSettings.defaults(cmd.tenantId()));
        if (current.version() != cmd.version()) {
            throw new lms.sales.shared.domain.SalesException(lms.sales.shared.domain.ErrorCode.SETTINGS_VERSION_CONFLICT,
                    "Cấu hình đã được người khác thay đổi");
        }
        var now = clock.instant();
        InvoiceSettings next = current.update(cmd.sellerLegalName(), cmd.sellerTaxCode(), cmd.sellerAddress(),
                cmd.sellerPhone(), cmd.sellerEmail(), cmd.templateCode(), cmd.series(), cmd.autoIssueOnCompletion(),
                now, cmd.actor());
        InvoiceSettings saved = port.save(next);
        audit.record(new SalesAuditPort.AuditEntry(cmd.tenantId(), SalesAuditPort.Actions.INVOICE_SETTINGS_CHANGED,
                "InvoiceSettings", cmd.tenantId(), cmd.actor(), now, Map.of("templateCode", saved.templateCode(),
                "series", saved.series(), "autoIssueOnCompletion", saved.autoIssueOnCompletion())));
        return InvoiceSettingsView.from(saved);
    }
}
