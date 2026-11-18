package lms.sales.ordering.application;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lms.sales.ordering.domain.PaymentMethod;
import lms.sales.ordering.domain.SalesOrder;
import lms.sales.shared.domain.DuplicateClientRequestException;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.application.CatalogLookupPort;
import lms.sales.stock.application.CatalogLookupPort.CatalogTitle;

/**
 * Tạo đơn POS. KHÔNG transactional: kiểm đầu vào, trả đơn cũ nếu clientRequestId đã dùng, tra catalog (HTTP) trước,
 * rồi mới gọi {@link CommitPosOrderUseCase} (transactional) để không giữ connection DB khi chờ HTTP.
 * {@link #create} từ chối giảm giá (DISCOUNT_NOT_ALLOWED); {@link #createWithDiscount} cho phép (khóa riêng ở web).
 */
public class PlacePosOrderUseCase {

    static final int MAX_LINES = 200;
    static final int MAX_QUANTITY = 10_000;

    private final SalesOrderRepository orders;
    private final CatalogLookupPort catalog;
    private final CommitPosOrderUseCase commit;
    /**
     * Luồng hai bước cho mọi phương thức (ADR-0021). Null chỉ khi không nối (test cũ): đơn thu ngay như trước, đơn QR
     * bị từ chối 400 PAYMENT_NOT_ENABLED.
     */
    private final CommitPosPendingOrderUseCase qrCommit;

    public PlacePosOrderUseCase(SalesOrderRepository orders, CatalogLookupPort catalog,
                                CommitPosOrderUseCase commit) {
        this(orders, catalog, commit, null);
    }

    public PlacePosOrderUseCase(SalesOrderRepository orders, CatalogLookupPort catalog,
                                CommitPosOrderUseCase commit, CommitPosPendingOrderUseCase qrCommit) {
        this.orders = orders;
        this.catalog = catalog;
        this.commit = commit;
        this.qrCommit = qrCommit;
    }

    public OrderResponse create(PlacePosOrderCommand cmd) {
        return createWithPayment(cmd).order();
    }

    public OrderResponse createWithDiscount(PlacePosOrderCommand cmd) {
        return createWithDiscountAndPayment(cmd).order();
    }

    /** Như {@link #create} kèm lệnh thanh toán khi đơn trả bằng QR (paymentIntent null với phương thức khác). */
    public PosOrderResult createWithPayment(PlacePosOrderCommand cmd) {
        if (cmd.hasDiscount()) {
            throw new SalesException(ErrorCode.DISCOUNT_NOT_ALLOWED, "Endpoint này không cho phép giảm giá");
        }
        return place(cmd);
    }

    /** Như {@link #createWithDiscount} kèm lệnh thanh toán khi đơn trả bằng QR. */
    public PosOrderResult createWithDiscountAndPayment(PlacePosOrderCommand cmd) {
        return place(cmd);
    }

    private PosOrderResult place(PlacePosOrderCommand cmd) {
        validate(cmd);
        boolean twoStep = qrCommit != null;
        if (cmd.paymentMethod().isGateway() && !twoStep) {
            throw new SalesException(ErrorCode.PAYMENT_NOT_ENABLED, "Thanh toán QR chưa được bật");
        }
        var existing = orders.findByClientRequestId(cmd.tenantId(), cmd.clientRequestId());
        if (existing.isPresent()) {
            return replay(cmd, existing.get());
        }
        List<UUID> titleIds = cmd.lines().stream().map(PlacePosOrderCommand.Line::titleId).distinct().toList();
        Map<UUID, CatalogTitle> titles = catalog.lookup(titleIds);
        try {
            return twoStep ? qrCommit.execute(cmd, titles) : new PosOrderResult(commit.execute(cmd, titles), null);
        } catch (DuplicateClientRequestException e) {
            return orders.findByClientRequestId(cmd.tenantId(), cmd.clientRequestId())
                    .map(o -> replay(cmd, o)).orElseThrow(() -> e);
        }
    }

    private PosOrderResult replay(PlacePosOrderCommand cmd, SalesOrder existing) {
        if (qrCommit != null) {
            return qrCommit.replay(cmd.tenantId(), existing);
        }
        return new PosOrderResult(OrderResponse.from(existing), null);
    }

    private static void validate(PlacePosOrderCommand cmd) {
        if (cmd.clientRequestId() == null) {
            throw SalesException.invalidRequest("Thiếu clientRequestId");
        }
        if (cmd.paymentMethod() == null || !cmd.paymentMethod().allowedForPos()) {
            throw SalesException.invalidRequest(
                    "Phương thức thanh toán phải là tiền mặt, chuyển khoản, thẻ hoặc QR");
        }
        if (cmd.lines().isEmpty() || cmd.lines().size() > MAX_LINES) {
            throw SalesException.invalidRequest("Đơn cần từ 1 đến " + MAX_LINES + " dòng");
        }
        Set<UUID> ignored = new HashSet<>();
        for (var l : cmd.lines()) {
            if (l.titleId() == null) {
                throw SalesException.invalidRequest("Thiếu đầu sách");
            }
            if (l.quantity() <= 0 || l.quantity() > MAX_QUANTITY) {
                throw SalesException.invalidQuantity("Số lượng phải từ 1 đến " + MAX_QUANTITY);
            }
            ignored.add(l.titleId());
        }
    }
}
