package lms.sales.ordering.application;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

    public PlacePosOrderUseCase(SalesOrderRepository orders, CatalogLookupPort catalog,
                                CommitPosOrderUseCase commit) {
        this.orders = orders;
        this.catalog = catalog;
        this.commit = commit;
    }

    public OrderResponse create(PlacePosOrderCommand cmd) {
        if (cmd.hasDiscount()) {
            throw new SalesException(ErrorCode.DISCOUNT_NOT_ALLOWED, "Endpoint này không cho phép giảm giá");
        }
        return place(cmd);
    }

    public OrderResponse createWithDiscount(PlacePosOrderCommand cmd) {
        return place(cmd);
    }

    private OrderResponse place(PlacePosOrderCommand cmd) {
        validate(cmd);
        var existing = orders.findByClientRequestId(cmd.tenantId(), cmd.clientRequestId());
        if (existing.isPresent()) {
            return OrderResponse.from(existing.get());
        }
        List<UUID> titleIds = cmd.lines().stream().map(PlacePosOrderCommand.Line::titleId).distinct().toList();
        Map<UUID, CatalogTitle> titles = catalog.lookup(titleIds);
        try {
            return commit.execute(cmd, titles);
        } catch (DuplicateClientRequestException e) {
            return orders.findByClientRequestId(cmd.tenantId(), cmd.clientRequestId())
                    .map(OrderResponse::from).orElseThrow(() -> e);
        }
    }

    private static void validate(PlacePosOrderCommand cmd) {
        if (cmd.clientRequestId() == null) {
            throw SalesException.invalidRequest("Thiếu clientRequestId");
        }
        if (cmd.paymentMethod() == null || !cmd.paymentMethod().allowedForPos()) {
            throw SalesException.invalidRequest("Phương thức thanh toán phải là tiền mặt, chuyển khoản hoặc thẻ");
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
