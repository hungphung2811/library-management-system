package lms.sales.ordering.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lms.sales.ordering.domain.Fulfilment;
import lms.sales.ordering.domain.OrderStatus;
import lms.sales.ordering.domain.PaymentIntent;
import lms.sales.ordering.domain.PaymentIntentStatus;
import lms.sales.ordering.domain.PaymentMethod;
import lms.sales.ordering.domain.SalesOrder;
import lms.sales.settings.application.GetSalesSettingsUseCase;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;

/**
 * Thanh toán trực tuyến của đơn online cho khách (ADR-0029). KHÔNG transactional: có lời gọi HTTP ra cổng (SePay) nên
 * không được giữ transaction mở; phần ghi nằm trong {@link ProcessPaymentWebhookUseCase} và {@link PaymentIntentUseCases}
 * (mỗi cái một transaction của sales_db). {@code customerUserId} lấy từ token; đơn của người khác hoặc không tồn tại
 * cùng trả 404 ORDER_NOT_FOUND. Endpoint đọc chỉ {@code @AuthenticatedOnly}, quyền sở hữu kiểm ở đây.
 */
public class ShopPaymentUseCases {

    /** Phương thức khách chọn được cho một đơn, kèm hình thức nhận hàng đi được với nó. */
    public record PaymentOption(PaymentMethod method, List<Fulfilment> fulfilments, Integer intentTtlMinutes) {
    }

    public record PaymentOptions(List<PaymentOption> methods, long deliveryFee) {
    }

    private final SalesOrderRepository orders;
    private final PaymentIntentRepository intents;
    private final SyncGatewayPaymentUseCase gatewaySync;
    private final PaymentIntentUseCases intentUseCases;
    private final GetSalesSettingsUseCase settings;
    private final Clock clock;
    private final boolean sepayOnlineEnabled;
    private final int intentTtlMinutes;

    public ShopPaymentUseCases(SalesOrderRepository orders, PaymentIntentRepository intents,
                               SyncGatewayPaymentUseCase gatewaySync, PaymentIntentUseCases intentUseCases,
                               GetSalesSettingsUseCase settings, Clock clock, boolean sepayOnlineEnabled,
                               int intentTtlMinutes) {
        this.orders = orders;
        this.intents = intents;
        this.gatewaySync = gatewaySync;
        this.intentUseCases = intentUseCases;
        this.settings = settings;
        this.clock = clock;
        this.sepayOnlineEnabled = sepayOnlineEnabled;
        this.intentTtlMinutes = intentTtlMinutes;
    }

    /**
     * Trạng thái thanh toán của đơn: đọc lệnh từ DB; lệnh còn PENDING thì hỏi cổng (qua bộ hãm, cổng lỗi thì giữ
     * nguyên); vừa PAID thì đọc lại đơn để trả trạng thái mới.
     */
    public ShopPaymentResponse myOrderPayment(UUID tenantId, UUID customerUserId, UUID orderId) {
        SalesOrder order = own(tenantId, customerUserId, orderId);
        PaymentIntentResponse intent = intents.findByOrderId(tenantId, order.id())
                .filter(i -> !PaymentIntent.PROVIDER_MANUAL.equals(i.provider()))
                .map(i -> PaymentIntentResponse.from(i, clock.instant())).orElse(null);
        if (intent != null && intent.status() == PaymentIntentStatus.PENDING) {
            PaymentIntentResponse after = gatewaySync.refresh(tenantId, intent);
            if (after.status() == PaymentIntentStatus.PAID) {
                intent = after;
                order = own(tenantId, customerUserId, orderId);
            }
        }
        return new ShopPaymentResponse(order.id(), order.orderNo(), order.status(), order.paymentStatus(),
                order.paymentMethod(), intent);
    }

    /**
     * Khách hủy đơn đang AWAITING_PAYMENT: hỏi cổng lần cuối; cổng báo đã nhận tiền thì ghi PAID và trả 409
     * ORDER_STATE_CONFLICT (FE đọc lại đơn); không thì hủy lệnh + đơn + nhả tồn (transaction riêng). Đơn không còn
     * AWAITING_PAYMENT (đã trả hoặc đã hết hạn) cũng 409.
     */
    public ShopOrderResponse cancelAwaiting(UUID tenantId, UUID customerUserId, UUID orderId, String reason) {
        SalesOrder order = own(tenantId, customerUserId, orderId);
        if (order.status() != OrderStatus.AWAITING_PAYMENT) {
            throw new SalesException(ErrorCode.ORDER_STATE_CONFLICT, "Trạng thái đơn không cho phép thao tác");
        }
        PaymentIntent intent = intents.findByOrderId(tenantId, order.id()).orElseThrow(
                () -> new SalesException(ErrorCode.ORDER_STATE_CONFLICT, "Trạng thái đơn không cho phép thao tác"));
        if (intent.status() == PaymentIntentStatus.PENDING && gatewaySync.settleBeforeClosing(tenantId, intent)) {
            throw new SalesException(ErrorCode.ORDER_STATE_CONFLICT,
                    "Đơn vừa được thanh toán nên không thể hủy, vui lòng liên hệ cửa hàng");
        }
        intentUseCases.cancel(tenantId, customerUserId, intent.id(),
                reason == null || reason.isBlank() ? "Khách hủy đơn" : reason);
        return ShopOrderResponse.from(own(tenantId, customerUserId, orderId));
    }

    /** Phương thức thanh toán website có thể dùng (SEPAY chỉ có khi cờ bật) và phí giao hàng cố định của tenant. */
    public PaymentOptions paymentOptions(UUID tenantId) {
        List<PaymentOption> methods = new ArrayList<>();
        methods.add(new PaymentOption(PaymentMethod.COD, List.of(Fulfilment.DELIVERY), null));
        methods.add(new PaymentOption(PaymentMethod.CASH, List.of(Fulfilment.PICKUP), null));
        methods.add(new PaymentOption(PaymentMethod.BANK_TRANSFER, List.of(Fulfilment.PICKUP, Fulfilment.DELIVERY),
                null));
        if (sepayOnlineEnabled) {
            methods.add(new PaymentOption(PaymentMethod.SEPAY, List.of(Fulfilment.PICKUP, Fulfilment.DELIVERY),
                    intentTtlMinutes));
        }
        return new PaymentOptions(List.copyOf(methods), settings.execute(tenantId).fixedShippingFee());
    }

    private SalesOrder own(UUID tenantId, UUID customerUserId, UUID orderId) {
        if (customerUserId == null) {
            throw SalesException.invalidRequest("Cần đăng nhập bằng tài khoản khách");
        }
        return orders.findByIdAndCustomer(tenantId, orderId, customerUserId)
                .orElseThrow(() -> new SalesException(ErrorCode.ORDER_NOT_FOUND, "Không có đơn hàng"));
    }
}
