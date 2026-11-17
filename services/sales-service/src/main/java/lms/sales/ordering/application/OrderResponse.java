package lms.sales.ordering.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.sales.ordering.domain.Fulfilment;
import lms.sales.ordering.domain.OrderChannel;
import lms.sales.ordering.domain.OrderLine;
import lms.sales.ordering.domain.OrderStatus;
import lms.sales.ordering.domain.PaymentMethod;
import lms.sales.ordering.domain.PaymentStatus;
import lms.sales.shared.domain.PersonalDataMasker;
import lms.sales.ordering.domain.Recipient;
import lms.sales.ordering.domain.SalesOrder;

/**
 * Đơn trả về cho FE (liên hệ khách và người nhận đã che; xem đầy đủ qua GetOrderContactUseCase, khách xem đơn của
 * mình qua {@link ShopOrderResponse}). Tiền là số đồng.
 */
public record OrderResponse(UUID id, String orderNo, OrderChannel channel, OrderStatus status,
                            PaymentStatus paymentStatus, PaymentMethod paymentMethod, Fulfilment fulfilment,
                            UUID customerUserId, String walkInNameMasked, String walkInPhoneMasked,
                            Instant piiAnonymizedAt, int vatRateBps, boolean pricesIncludeVat, String currency,
                            long subtotal, long discountTotal, long vatTotal, long shippingFee, long grandTotal,
                            Instant createdAt, Instant completedAt, long version, List<Line> lines,
                            String recipientNameMasked, String recipientPhoneMasked, String recipientAddressMasked,
                            Instant confirmedAt, Instant paidAt, Instant cancelledAt, String cancelReason,
                            UUID exchangeOfReturnId) {

    public record Line(UUID id, int lineNo, UUID titleId, String titleName, String isbn, int quantity,
                       long unitPrice, long lineDiscount, long allocatedOrderDiscount, long amount, long vatAmount,
                       long lineTotal) {
        static Line from(OrderLine l) {
            return new Line(l.id(), l.lineNo(), l.titleId(), l.titleName(), l.isbn(), l.quantity(),
                    l.unitPrice().amount(), l.lineDiscount().amount(), l.allocatedOrderDiscount().amount(),
                    l.amount().amount(), l.vatAmount().amount(), l.lineTotal().amount());
        }
    }

    public static OrderResponse from(SalesOrder o) {
        Recipient r = o.recipient();
        return new OrderResponse(o.id(), o.orderNo(), o.channel(), o.status(), o.paymentStatus(),
                o.paymentMethod(), o.fulfilment(), o.customerUserId(), PersonalDataMasker.maskName(o.walkInName()),
                PersonalDataMasker.maskPhone(o.walkInPhone()), o.piiAnonymizedAt(), o.vatRateBps(),
                o.pricesIncludeVat(), lms.sales.shared.domain.Money.CURRENCY, o.subtotal().amount(),
                o.discountTotal().amount(), o.vatTotal().amount(), o.shippingFee().amount(),
                o.grandTotal().amount(), o.createdAt(), o.completedAt(), o.version(),
                o.lines().stream().map(Line::from).toList(),
                r == null ? null : PersonalDataMasker.maskName(r.name()),
                r == null ? null : PersonalDataMasker.maskPhone(r.phone()),
                r == null ? null : PersonalDataMasker.maskAddress(r.address()),
                o.confirmedAt(), o.paidAt(), o.cancelledAt(), o.cancelReason(), o.exchangeOfReturnId());
    }
}
