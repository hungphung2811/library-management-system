package lms.sales.invoicing.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.sales.invoicing.domain.Invoice;
import lms.sales.invoicing.domain.InvoiceKind;
import lms.sales.invoicing.domain.InvoiceStatus;
import lms.sales.shared.application.PageResult;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import org.springframework.transaction.annotation.Transactional;

/** Đọc hóa đơn: danh sách, chi tiết, bản in. Chỉ đọc. */
public class InvoiceQueryUseCases {

    /** {@code page} bắt đầu từ 0; {@code from} bao gồm, {@code to} loại trừ (theo thời điểm tạo). */
    public record SearchQuery(InvoiceStatus status, InvoiceKind kind, Instant from, Instant to, String keyword,
                              int page, int size) {
    }

    private final InvoiceRepository invoices;
    private final InvoiceRendererPort renderer;

    public InvoiceQueryUseCases(InvoiceRepository invoices, InvoiceRendererPort renderer) {
        this.invoices = invoices;
        this.renderer = renderer;
    }

    @Transactional(readOnly = true)
    public PageResult<InvoiceSummary> search(UUID tenantId, SearchQuery q) {
        return invoices.search(tenantId, new InvoiceRepository.Criteria(q.status(), q.kind(), q.from(), q.to(),
                q.keyword() == null || q.keyword().isBlank() ? null : q.keyword().trim()), q.page(), q.size());
    }

    @Transactional(readOnly = true)
    public InvoiceView get(UUID tenantId, UUID id) {
        Invoice invoice = invoices.findById(tenantId, id)
                .orElseThrow(() -> new SalesException(ErrorCode.INVOICE_NOT_FOUND, "Không có hóa đơn"));
        Invoice original = invoice.originalInvoiceId() == null ? null
                : invoices.findById(tenantId, invoice.originalInvoiceId()).orElse(null);
        List<Invoice> adjustments = invoice.kind().isPrimary()
                ? invoices.findActiveAdjustments(tenantId, invoice.id()) : List.of();
        return InvoiceView.of(invoice, original, adjustments);
    }

    /** Bản in (HTML in được). Hóa đơn nháp không in (chưa có số). */
    @Transactional(readOnly = true)
    public InvoiceRendererPort.Rendered print(UUID tenantId, UUID id) {
        InvoiceView view = get(tenantId, id);
        if (InvoiceStatus.DRAFT.name().equals(view.status())) {
            throw new SalesException(ErrorCode.INVOICE_STATE_CONFLICT, "Hóa đơn nháp chưa có số, chưa in được");
        }
        return renderer.render(view);
    }
}
