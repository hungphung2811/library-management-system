package lms.sales.invoicing.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lms.sales.invoicing.domain.BuyerInfo;
import lms.sales.invoicing.domain.InvoiceRequest;
import lms.sales.invoicing.domain.RequestSource;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Yêu cầu xuất hóa đơn (ADR-0030 D2): nhân viên nhập ở quầy hoặc khách gửi cho đơn online CỦA CHÍNH MÌNH (đơn người
 * khác, đơn POS hoặc không tồn tại đều 404 ORDER_NOT_FOUND cùng một thân: không lộ sự tồn tại của đơn). Mỗi đơn một yêu
 * cầu; còn OPEN thì ghi đè được, đã có hóa đơn thì 409 INVOICE_ALREADY_EXISTS (dùng thay thế hóa đơn). Đơn đã hủy thì
 * 409 ORDER_NOT_INVOICEABLE. Thông tin người mua là dữ liệu cá nhân: audit chỉ ghi có/không MST, không ghi nội dung.
 */
public class InvoiceRequestUseCases {

    private final InvoiceOrderSourcePort orders;
    private final InvoiceRequestRepository requests;
    private final InvoiceAutoIssuePort autoIssue;
    private final SalesAuditPort audit;
    private final Clock clock;

    public InvoiceRequestUseCases(InvoiceOrderSourcePort orders, InvoiceRequestRepository requests,
                                  InvoiceAutoIssuePort autoIssue, SalesAuditPort audit, Clock clock) {
        this.orders = orders;
        this.requests = requests;
        this.autoIssue = autoIssue;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public InvoiceRequestView get(UUID tenantId, UUID orderId) {
        orders.find(tenantId, orderId).orElseThrow(UseCaseErrors::orderNotFound);
        return requests.findByOrder(tenantId, orderId).map(InvoiceRequestView::from)
                .orElseThrow(() -> new SalesException(ErrorCode.INVOICE_NOT_FOUND, "Đơn chưa có yêu cầu xuất hóa đơn"));
    }

    /** Nhân viên nhập/sửa yêu cầu của một đơn bất kỳ trong tenant. */
    @Transactional
    public InvoiceRequestView saveByStaff(UUID tenantId, UUID actor, UUID orderId, BuyerInput buyer) {
        InvoiceOrder order = orders.find(tenantId, orderId).orElseThrow(UseCaseErrors::orderNotFound);
        return save(tenantId, actor, order, buyer);
    }

    /** Khách gửi/sửa yêu cầu cho đơn ONLINE của mình. */
    @Transactional
    public InvoiceRequestView saveByCustomer(UUID tenantId, UUID customerUserId, UUID orderId, BuyerInput buyer) {
        InvoiceOrder order = orders.findOwnedOnline(tenantId, orderId, customerUserId)
                .orElseThrow(UseCaseErrors::orderNotFound);
        return save(tenantId, customerUserId, order, buyer);
    }

    private InvoiceRequestView save(UUID tenantId, UUID actor, InvoiceOrder order, BuyerInput input) {
        if (order.isCancelled()) {
            throw new SalesException(ErrorCode.ORDER_NOT_INVOICEABLE, "Đơn đã hủy, không nhận yêu cầu xuất hóa đơn");
        }
        BuyerInfo buyer = input.toBuyer().requireIdentified();
        Instant now = clock.instant();
        var existing = requests.findByOrder(tenantId, order.orderId());
        InvoiceRequest request;
        if (existing.isPresent()) {
            request = existing.get();
            request.update(buyer, now);
        } else {
            request = InvoiceRequest.open(tenantId, order.orderId(), buyer,
                    "ONLINE".equals(order.channel()) ? RequestSource.ONLINE : RequestSource.POS, actor, now);
        }
        InvoiceRequest saved = requests.save(request);
        audit.record(new SalesAuditPort.AuditEntry(tenantId, SalesAuditPort.Actions.INVOICE_REQUEST_SAVED,
                "InvoiceRequest", saved.id(), actor, now, Map.of("orderId", order.orderId().toString(),
                "source", saved.source().name(), "hasTaxCode", buyer.taxCode() != null)));
        // Yêu cầu đến sau khi đơn đã hoàn tất: tự phát hành nếu tenant bật (không làm gì nếu chưa đủ điều kiện)
        if (order.isCompletedAndPaid()) {
            autoIssue.onOrderSettled(tenantId, order.orderId(), actor);
        }
        return InvoiceRequestView.from(requests.findByOrder(tenantId, order.orderId()).orElse(saved));
    }
}
