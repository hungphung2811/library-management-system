package lms.sales.ordering.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.sales.ordering.domain.OrderChannel;
import lms.sales.ordering.domain.SalesOrder;
import lms.sales.settings.application.OrderAbuseLimits;
import lms.sales.settings.application.OrderAbusePolicyUseCases;
import lms.sales.shared.domain.DuplicateClientRequestException;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.application.CatalogLookupPort;
import lms.sales.stock.application.CatalogLookupPort.CatalogTitle;

/**
 * Khách đặt đơn online. KHÔNG transactional (như POS): kiểm đầu vào, trả đơn cũ nếu clientRequestId đã dùng, tra
 * catalog (HTTP) trước, rồi gọi {@link CommitOnlineOrderUseCase} (một transaction: giữ tồn reserved, đơn, audit).
 */
public class PlaceOnlineOrderUseCase {

    static final int MAX_LINES = 200;
    static final int MAX_QUANTITY = 10_000;

    private final SalesOrderRepository orders;
    private final CatalogLookupPort catalog;
    private final CommitOnlineOrderUseCase commit;
    private final OrderAbusePolicyUseCases policies;

    public PlaceOnlineOrderUseCase(SalesOrderRepository orders, CatalogLookupPort catalog,
                                   CommitOnlineOrderUseCase commit, OrderAbusePolicyUseCases policies) {
        this.orders = orders;
        this.catalog = catalog;
        this.commit = commit;
        this.policies = policies;
    }

    public ShopOrderResponse place(PlaceOnlineOrderCommand cmd) {
        validate(cmd);
        var existing = orders.findByClientRequestId(cmd.tenantId(), cmd.clientRequestId());
        if (existing.isPresent()) {
            return replay(cmd, existing.get());
        }
        checkQuantities(cmd, policies.current(cmd.tenantId()));
        List<UUID> titleIds = cmd.lines().stream().map(PlaceOnlineOrderCommand.Line::titleId).distinct().toList();
        Map<UUID, CatalogTitle> titles = catalog.lookup(titleIds);
        try {
            return commit.execute(cmd, titles);
        } catch (DuplicateClientRequestException e) {
            return orders.findByClientRequestId(cmd.tenantId(), cmd.clientRequestId())
                    .map(o -> replay(cmd, o)).orElseThrow(() -> e);
        }
    }

    /**
     * Gửi lại cùng clientRequestId trả đơn cũ của CHÍNH khách khi nội dung giống hệt; khóa đã gắn với người khác (hoặc
     * đơn POS) thì 409 CLIENT_REQUEST_CONFLICT, không kèm dữ liệu; cùng khách nhưng khác nội dung thì 422
     * IDEMPOTENCY_KEY_REUSED. Đơn cũ chưa lưu mã băm (trước V13) thì chỉ so chủ sở hữu như trước.
     */
    static ShopOrderResponse replay(PlaceOnlineOrderCommand cmd, SalesOrder o) {
        if (o.channel() != OrderChannel.ONLINE || !cmd.customerUserId().equals(o.customerUserId())) {
            throw new SalesException(ErrorCode.CLIENT_REQUEST_CONFLICT, "clientRequestId đã được dùng");
        }
        if (o.requestHash() != null && !o.requestHash().equals(OrderRequestFingerprint.of(cmd))) {
            throw new SalesException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "clientRequestId đã được dùng cho một đơn có nội dung khác");
        }
        return ShopOrderResponse.from(o);
    }

    /** Trần số lượng theo chính sách tenant (kiểm trước khi gọi catalog để từ chối sớm, rẻ). */
    private static void checkQuantities(PlaceOnlineOrderCommand cmd, OrderAbuseLimits policy) {
        long total = 0;
        for (var l : cmd.lines()) {
            if (l.quantity() > policy.maxQuantityPerLine()) {
                throw SalesException.invalidQuantity("Mỗi dòng đặt tối đa " + policy.maxQuantityPerLine() + " cuốn");
            }
            total += l.quantity();
        }
        if (total > policy.maxTotalQuantity()) {
            throw SalesException.invalidQuantity("Mỗi đơn đặt tối đa " + policy.maxTotalQuantity() + " cuốn");
        }
    }

    private static void validate(PlaceOnlineOrderCommand cmd) {
        if (cmd.clientRequestId() == null) {
            throw SalesException.invalidRequest("Thiếu clientRequestId");
        }
        if (cmd.customerUserId() == null) {
            throw SalesException.invalidRequest("Đơn online cần khách đã đăng nhập");
        }
        if (cmd.lines().isEmpty() || cmd.lines().size() > MAX_LINES) {
            throw SalesException.invalidRequest("Đơn cần từ 1 đến " + MAX_LINES + " dòng");
        }
        for (var l : cmd.lines()) {
            if (l.titleId() == null) {
                throw SalesException.invalidRequest("Thiếu đầu sách");
            }
            if (l.quantity() <= 0 || l.quantity() > MAX_QUANTITY) {
                throw SalesException.invalidQuantity("Số lượng phải từ 1 đến " + MAX_QUANTITY);
            }
        }
    }
}
