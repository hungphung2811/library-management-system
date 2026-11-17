package lms.sales.ordering.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.sales.ordering.domain.OrderChannel;
import lms.sales.ordering.domain.SalesOrder;
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

    public PlaceOnlineOrderUseCase(SalesOrderRepository orders, CatalogLookupPort catalog,
                                   CommitOnlineOrderUseCase commit) {
        this.orders = orders;
        this.catalog = catalog;
        this.commit = commit;
    }

    public ShopOrderResponse place(PlaceOnlineOrderCommand cmd) {
        validate(cmd);
        var existing = orders.findByClientRequestId(cmd.tenantId(), cmd.clientRequestId());
        if (existing.isPresent()) {
            return replay(cmd, existing.get());
        }
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
     * Gửi lại cùng clientRequestId trả đơn cũ của CHÍNH khách; khóa đã gắn với người khác (hoặc đơn POS) thì
     * 409 CLIENT_REQUEST_CONFLICT, không kèm dữ liệu.
     */
    static ShopOrderResponse replay(PlaceOnlineOrderCommand cmd, SalesOrder o) {
        if (o.channel() != OrderChannel.ONLINE || !cmd.customerUserId().equals(o.customerUserId())) {
            throw new SalesException(ErrorCode.CLIENT_REQUEST_CONFLICT, "clientRequestId đã được dùng");
        }
        return ShopOrderResponse.from(o);
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
