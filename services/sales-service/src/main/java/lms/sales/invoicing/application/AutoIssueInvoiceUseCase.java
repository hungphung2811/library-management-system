package lms.sales.invoicing.application;

import java.util.UUID;
import lms.sales.invoicing.domain.InvoiceSettings;
import lms.sales.invoicing.domain.RequestStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tự phát hành hóa đơn khi đơn vừa thành COMPLETED + PAID (ADR-0030 D2), chạy trong transaction ghi nhận thanh toán/hoàn
 * tất. Chỉ làm khi tenant bật {@code autoIssueOnCompletion}, cấu hình người bán đã đủ, đơn có yêu cầu xuất hóa đơn đang
 * mở và chưa có hóa đơn còn hiệu lực. Mọi điều kiện được KIỂM TRƯỚC (không dựa vào bắt exception: ngoại lệ ném qua một
 * use case transactional khác sẽ đánh dấu rollback cả transaction thanh toán); thiếu cấu hình chỉ ghi log WARN rồi bỏ
 * qua, nhân viên xuất tay sau.
 */
public class AutoIssueInvoiceUseCase implements InvoiceAutoIssuePort {

    private static final Logger log = LoggerFactory.getLogger(AutoIssueInvoiceUseCase.class);

    private final InvoiceSettingsPort settings;
    private final InvoiceRequestRepository requests;
    private final InvoiceRepository invoices;
    private final InvoiceOrderSourcePort orders;
    private final InvoiceUseCases useCases;

    public AutoIssueInvoiceUseCase(InvoiceSettingsPort settings, InvoiceRequestRepository requests,
                                   InvoiceRepository invoices, InvoiceOrderSourcePort orders,
                                   InvoiceUseCases useCases) {
        this.settings = settings;
        this.requests = requests;
        this.invoices = invoices;
        this.orders = orders;
        this.useCases = useCases;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void onOrderSettled(UUID tenantId, UUID orderId, UUID actor) {
        InvoiceSettings cfg = settings.find(tenantId).orElse(InvoiceSettings.defaults(tenantId));
        if (!cfg.autoIssueOnCompletion()) {
            return;
        }
        var request = requests.findByOrder(tenantId, orderId);
        if (request.isEmpty() || request.get().status() != RequestStatus.OPEN) {
            return;
        }
        if (!cfg.isComplete()) {
            log.warn("Bỏ qua tự phát hành hóa đơn: chưa cấu hình người bán (tenant {}, đơn {})", tenantId, orderId);
            return;
        }
        var order = orders.find(tenantId, orderId);
        if (order.isEmpty() || !order.get().isCompletedAndPaid() || order.get().hasReturns()
                || invoices.findEffectiveForOrder(tenantId, orderId).isPresent()) {
            return;
        }
        useCases.issue(new InvoiceUseCases.IssueCommand(tenantId, orderId, actor, null));
    }
}
