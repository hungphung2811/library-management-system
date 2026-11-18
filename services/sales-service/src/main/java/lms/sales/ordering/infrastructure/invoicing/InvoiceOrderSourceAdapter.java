package lms.sales.ordering.infrastructure.invoicing;

import java.util.Optional;
import java.util.UUID;
import lms.sales.invoicing.application.InvoiceOrder;
import lms.sales.invoicing.application.InvoiceOrderSourcePort;
import lms.sales.ordering.application.SalesOrderRepository;
import lms.sales.ordering.application.SalesReturnRepository;
import lms.sales.ordering.domain.OrderLine;
import lms.sales.ordering.domain.SalesOrder;
import lms.sales.shared.domain.Money;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cài đặt cổng đọc đơn của context invoicing (ADR-0030 gói A). Nằm ở ordering để ordering chỉ phụ thuộc tầng
 * application của invoicing (không có vòng phụ thuộc). Chỉ đọc/khóa đơn qua các port của ordering, không ghi.
 */
@Component
public class InvoiceOrderSourceAdapter implements InvoiceOrderSourcePort {

    private final SalesOrderRepository orders;
    private final SalesReturnRepository returns;

    public InvoiceOrderSourceAdapter(SalesOrderRepository orders, SalesReturnRepository returns) {
        this.orders = orders;
        this.returns = returns;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InvoiceOrder> find(UUID tenantId, UUID orderId) {
        return orders.findById(tenantId, orderId).map(o -> toInvoiceOrder(tenantId, o));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InvoiceOrder> findOwnedOnline(UUID tenantId, UUID orderId, UUID customerUserId) {
        return orders.findByIdAndCustomer(tenantId, orderId, customerUserId).map(o -> toInvoiceOrder(tenantId, o));
    }

    @Override
    @Transactional
    public void lock(UUID tenantId, UUID orderId) {
        orders.lockLines(tenantId, orderId);
    }

    private InvoiceOrder toInvoiceOrder(UUID tenantId, SalesOrder o) {
        boolean hasReturns = !returns.priorByOrderLine(tenantId, o.id()).isEmpty();
        return new InvoiceOrder(o.id(), o.orderNo(), o.channel().name(), o.status().name(), o.paymentStatus().name(),
                o.paymentMethod() == null ? null : o.paymentMethod().name(), o.customerUserId(),
                o.pricesIncludeVat(), o.vatRateBps(), o.shippingFee(), o.grandTotal(), hasReturns,
                o.lines().stream().map(InvoiceOrderSourceAdapter::toLine).toList());
    }

    private static InvoiceOrder.Line toLine(OrderLine l) {
        return new InvoiceOrder.Line(l.id(), l.titleName(), l.isbn(), l.quantity(), l.unitPrice(),
                l.lineDiscount().plus(l.allocatedOrderDiscount()), l.vatAmount(), l.lineTotal());
    }
}
