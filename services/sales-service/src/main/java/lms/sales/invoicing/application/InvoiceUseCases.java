package lms.sales.invoicing.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.sales.invoicing.domain.BuyerInfo;
import lms.sales.invoicing.domain.Invoice;
import lms.sales.invoicing.domain.InvoiceSettings;
import lms.sales.invoicing.domain.SellerInfo;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phát hành, hủy và thay thế hóa đơn (ADR-0030 D1). Mỗi thao tác một transaction của sales_db: khóa đơn (cùng khóa với
 * trả hàng) rồi kiểm điều kiện, cấp số, lưu, cập nhật yêu cầu, audit. Hóa đơn bất biến sau ISSUED: hủy chỉ chuyển trạng
 * thái; sửa thông tin là hủy và lập hóa đơn thay thế. Hóa đơn nội bộ chưa có giá trị pháp lý của hóa đơn điện tử.
 */
public class InvoiceUseCases {

    public record IssueCommand(UUID tenantId, UUID orderId, UUID actor, UUID clientRequestId) {
    }

    public record CancelCommand(UUID tenantId, UUID invoiceId, UUID actor, String reason) {
    }

    /** {@code buyer} null: giữ người mua của hóa đơn gốc. */
    public record ReplaceCommand(UUID tenantId, UUID invoiceId, UUID actor, String reason, BuyerInput buyer,
                                 UUID clientRequestId) {
    }

    private final InvoiceOrderSourcePort orders;
    private final InvoiceRepository invoices;
    private final InvoiceRequestRepository requests;
    private final InvoiceSettingsPort settings;
    private final InvoiceIssuing issuing;
    private final Clock clock;

    public InvoiceUseCases(InvoiceOrderSourcePort orders, InvoiceRepository invoices, InvoiceRequestRepository requests,
                           InvoiceSettingsPort settings, InvoiceIssuerPort issuer, SalesAuditPort audit,
                           Clock clock) {
        this.orders = orders;
        this.invoices = invoices;
        this.requests = requests;
        this.settings = settings;
        this.issuing = new InvoiceIssuing(issuer, invoices, audit);
        this.clock = clock;
    }

    /**
     * Lập hóa đơn cho đơn đã COMPLETED + PAID. Cùng {@code clientRequestId} gửi lại trả hóa đơn cũ. Đơn đã có hóa đơn
     * còn hiệu lực thì 409 INVOICE_ALREADY_EXISTS; đơn chưa đủ điều kiện (hoặc đã có phiếu trả) thì 409
     * ORDER_NOT_INVOICEABLE; chưa cấu hình người bán thì 409 INVOICE_SETTINGS_INCOMPLETE.
     */
    @Transactional
    public InvoiceView issue(IssueCommand cmd) {
        if (cmd.clientRequestId() != null) {
            var replay = invoices.findByClientRequestId(cmd.tenantId(), cmd.clientRequestId());
            if (replay.isPresent()) {
                if (!replay.get().orderId().equals(cmd.orderId())) {
                    throw new SalesException(ErrorCode.CLIENT_REQUEST_CONFLICT, "clientRequestId đã dùng cho đơn khác");
                }
                return view(cmd.tenantId(), replay.get());
            }
        }
        orders.lock(cmd.tenantId(), cmd.orderId());
        InvoiceOrder order = orders.find(cmd.tenantId(), cmd.orderId())
                .orElseThrow(() -> new SalesException(ErrorCode.ORDER_NOT_FOUND, "Không có đơn hàng"));
        requireInvoiceable(order);
        if (invoices.findEffectiveForOrder(cmd.tenantId(), cmd.orderId()).isPresent()) {
            throw new SalesException(ErrorCode.INVOICE_ALREADY_EXISTS, "Đơn đã có hóa đơn còn hiệu lực");
        }
        InvoiceSettings cfg = settings.find(cmd.tenantId()).orElse(InvoiceSettings.defaults(cmd.tenantId()));
        SellerInfo seller = cfg.requireSeller();
        var request = requests.findByOrder(cmd.tenantId(), cmd.orderId());
        BuyerInfo buyer = request.map(r -> r.buyer()).orElse(BuyerInfo.notRequested());
        Instant now = clock.instant();
        Invoice draft = Invoice.draftOriginal(UUID.randomUUID(), cmd.tenantId(), order.orderId(), order.orderNo(),
                cfg.templateCode(), cfg.series(), seller, buyer, order.pricesIncludeVat(), order.paymentMethod(),
                InvoiceIssuing.linesOf(order), cmd.actor(), now, cmd.clientRequestId());
        Invoice saved = issuing.issueAndSave(draft, cmd.actor(), now);
        request.ifPresent(r -> {
            r.fulfill(now);
            requests.save(r);
        });
        issuing.record(SalesAuditPort.Actions.INVOICE_ISSUED, saved, cmd.actor(), now, Map.of());
        return view(cmd.tenantId(), saved);
    }

    /** Hủy hóa đơn đã phát hành (lý do bắt buộc). Hóa đơn đã có điều chỉnh thì 409 INVOICE_HAS_ADJUSTMENTS. */
    @Transactional
    public InvoiceView cancel(CancelCommand cmd) {
        Invoice invoice = lockAndReload(cmd.tenantId(), cmd.invoiceId());
        Instant now = clock.instant();
        invoice.cancel(cmd.reason(), cmd.actor(), now, hasAdjustments(invoice));
        Invoice saved = invoices.update(invoice);
        issuing.record(SalesAuditPort.Actions.INVOICE_CANCELLED, saved, cmd.actor(), now, Map.of());
        return view(cmd.tenantId(), saved);
    }

    /**
     * Hủy hóa đơn gốc rồi lập hóa đơn thay thế ISSUED trong cùng transaction (thường để sửa thông tin người mua). Người
     * bán lấy theo cấu hình hiện tại; dòng sao từ hóa đơn gốc. Hóa đơn đã có điều chỉnh thì không thay thế được.
     */
    @Transactional
    public InvoiceView replace(ReplaceCommand cmd) {
        if (cmd.clientRequestId() != null) {
            var replay = invoices.findByClientRequestId(cmd.tenantId(), cmd.clientRequestId());
            if (replay.isPresent()) {
                if (!cmd.invoiceId().equals(replay.get().originalInvoiceId())) {
                    throw new SalesException(ErrorCode.CLIENT_REQUEST_CONFLICT, "clientRequestId đã dùng cho thao tác khác");
                }
                return view(cmd.tenantId(), replay.get());
            }
        }
        Invoice original = lockAndReload(cmd.tenantId(), cmd.invoiceId());
        Instant now = clock.instant();
        BuyerInfo buyer = cmd.buyer() == null ? original.buyer() : cmd.buyer().toBuyer();
        InvoiceSettings cfg = settings.find(cmd.tenantId()).orElse(InvoiceSettings.defaults(cmd.tenantId()));
        SellerInfo seller = cfg.requireSeller();
        original.cancel(cmd.reason(), cmd.actor(), now, hasAdjustments(original));
        invoices.update(original); // ghi hủy trước, rồi mới chèn bản thay thế (chỉ mục duy nhất "một hóa đơn hiệu lực/đơn")
        Invoice draft = Invoice.draftReplacement(UUID.randomUUID(), original, cfg.templateCode(), cfg.series(),
                seller, buyer, cmd.actor(), now, cmd.clientRequestId());
        Invoice saved = issuing.issueAndSave(draft, cmd.actor(), now);
        issuing.record(SalesAuditPort.Actions.INVOICE_CANCELLED, original, cmd.actor(), now,
                Map.of("replacedBy", saved.id().toString()));
        issuing.record(SalesAuditPort.Actions.INVOICE_REPLACED, saved, cmd.actor(), now,
                Map.of("originalInvoiceId", original.id().toString()));
        return view(cmd.tenantId(), saved);
    }

    /** Điều kiện lập: đơn COMPLETED + PAID và chưa có phiếu trả (hóa đơn tính trên toàn đơn). */
    static void requireInvoiceable(InvoiceOrder order) {
        if (!order.isCompletedAndPaid()) {
            throw new SalesException(ErrorCode.ORDER_NOT_INVOICEABLE, "Chỉ lập hóa đơn cho đơn đã hoàn tất và đã thu tiền");
        }
        if (order.hasReturns()) {
            throw new SalesException(ErrorCode.ORDER_NOT_INVOICEABLE,
                    "Đơn đã có phiếu trả trước khi lập hóa đơn: chưa hỗ trợ lập hóa đơn cho đơn đã trả một phần");
        }
    }

    /** Đọc hóa đơn, khóa đơn của nó (tuần tự hóa với trả hàng/phát hành), rồi đọc lại để thấy trạng thái mới nhất. */
    private Invoice lockAndReload(UUID tenantId, UUID invoiceId) {
        Invoice first = invoices.findById(tenantId, invoiceId)
                .orElseThrow(() -> new SalesException(ErrorCode.INVOICE_NOT_FOUND, "Không có hóa đơn"));
        orders.lock(tenantId, first.orderId());
        return invoices.findById(tenantId, invoiceId)
                .orElseThrow(() -> new SalesException(ErrorCode.INVOICE_NOT_FOUND, "Không có hóa đơn"));
    }

    private boolean hasAdjustments(Invoice invoice) {
        return invoice.kind().isPrimary() && !invoices.findActiveAdjustments(invoice.tenantId(), invoice.id()).isEmpty();
    }

    private InvoiceView view(UUID tenantId, Invoice invoice) {
        Invoice original = invoice.originalInvoiceId() == null ? null
                : invoices.findById(tenantId, invoice.originalInvoiceId()).orElse(null);
        List<Invoice> adjustments = invoice.kind().isPrimary()
                ? invoices.findActiveAdjustments(tenantId, invoice.id()) : List.of();
        return InvoiceView.of(invoice, original, adjustments);
    }
}
