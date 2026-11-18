package lms.sales.ordering.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.sales.ordering.domain.PriceCalculator;
import lms.sales.ordering.domain.SalesOrder;
import lms.sales.settings.application.GetSalesSettingsUseCase;
import lms.sales.settings.application.SalesSettingsView;
import lms.sales.shared.application.DocumentNumberPort;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.application.TenantDirectoryPort;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.application.CatalogLookupPort.CatalogTitle;
import lms.sales.stock.application.SellableItem;
import lms.sales.stock.application.StockLine;
import lms.sales.stock.application.StockService;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phần transactional của đơn online: giá, tính tiền, GIỮ tồn (reserved += q, kèm sổ cái RESERVE), lưu đơn
 * PENDING_CONFIRMATION, danh bạ tenant, audit, tất cả trong một transaction của sales_db. Hết hàng thì rollback cả đơn.
 */
public class CommitOnlineOrderUseCase {

    private final SalesOrderRepository orders;
    private final StockService stockService;
    private final GetSalesSettingsUseCase settingsUseCase;
    private final DocumentNumberPort numbers;
    private final SalesAuditPort audit;
    private final TenantDirectoryPort tenants;
    private final Clock clock;

    public CommitOnlineOrderUseCase(SalesOrderRepository orders, StockService stockService,
                                    GetSalesSettingsUseCase settingsUseCase, DocumentNumberPort numbers,
                                    SalesAuditPort audit, TenantDirectoryPort tenants, Clock clock) {
        this.orders = orders;
        this.stockService = stockService;
        this.settingsUseCase = settingsUseCase;
        this.numbers = numbers;
        this.audit = audit;
        this.tenants = tenants;
        this.clock = clock;
    }

    @Transactional
    public ShopOrderResponse execute(PlaceOnlineOrderCommand cmd, Map<UUID, CatalogTitle> titles) {
        var existing = orders.findByClientRequestId(cmd.tenantId(), cmd.clientRequestId());
        if (existing.isPresent()) {
            return PlaceOnlineOrderUseCase.replay(cmd, existing.get());
        }
        SalesSettingsView settings = settingsUseCase.execute(cmd.tenantId());
        Map<UUID, SellableItem> items = stockService.sellableItems(cmd.tenantId(),
                cmd.lines().stream().map(PlaceOnlineOrderCommand.Line::titleId).distinct().toList());
        List<SalesOrder.LineDraft> drafts = new ArrayList<>();
        for (var line : cmd.lines()) {
            SellableItem item = items.get(line.titleId());
            if (item == null) {
                throw SalesException.notRegistered(line.titleId());
            }
            CatalogTitle title = titles.get(line.titleId());
            if (!item.active() || title == null) {
                throw SalesException.titleNotAvailable(line.titleId());
            }
            Money price = item.sellingPrice() != null ? item.sellingPrice()
                    : Money.fromCoverPrice(title.coverPrice());
            drafts.add(new SalesOrder.LineDraft(line.titleId(), title.name(), title.isbn(), price, line.quantity(),
                    null));
        }
        var now = clock.instant();
        SalesOrder order = SalesOrder.placeOnline(cmd.tenantId(), numbers.next(cmd.tenantId(),
                        DocumentNumberPort.ORDER), cmd.customerUserId(), now, cmd.paymentMethod(), cmd.fulfilment(),
                cmd.recipient(), drafts, new PriceCalculator.TaxPolicy(settings.vatRate(),
                        settings.pricesIncludeVat()), cmd.clientRequestId(), cmd.customerNote());

        // Giữ tồn trong cùng transaction: hết hàng thì ném INSUFFICIENT_STOCK và rollback cả đơn
        stockService.reserveAll(cmd.tenantId(), cmd.customerUserId(),
                cmd.lines().stream().map(l -> new StockLine(l.titleId(), l.quantity())).toList(), "ORDER",
                order.id());
        SalesOrder saved = orders.save(order);
        tenants.touch(cmd.tenantId());
        audit.record(new SalesAuditPort.AuditEntry(cmd.tenantId(), SalesAuditPort.Actions.ORDER_CREATED, "SalesOrder",
                saved.id(), cmd.customerUserId(), now,
                Map.of("orderNo", saved.orderNo(), "channel", saved.channel().name(),
                        "paymentMethod", saved.paymentMethod().name(), "fulfilment", saved.fulfilment().name(),
                        "lines", saved.lines().size(), "grandTotal", saved.grandTotal().amount())));
        return ShopOrderResponse.from(saved);
    }
}
