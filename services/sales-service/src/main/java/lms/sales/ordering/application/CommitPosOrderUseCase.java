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
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.application.CatalogLookupPort.CatalogTitle;
import lms.sales.stock.application.StockLine;
import lms.sales.stock.application.StockService;
import lms.sales.stock.application.SellableItem;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phần transactional của đơn POS: giá, tính tiền, trừ kho (onHand, kèm sổ cái), lưu đơn, audit, tất cả trong một
 * transaction của sales_db. Hết hàng thì ném lỗi và rollback toàn bộ (không đơn một phần). Dòng đơn giữ nguyên thứ tự
 * đầu vào; việc trừ kho gộp dòng trùng titleId và đi theo titleId tăng dần.
 */
public class CommitPosOrderUseCase {

    private final SalesOrderRepository orders;
    private final StockService stockService;
    private final GetSalesSettingsUseCase settingsUseCase;
    private final DocumentNumberPort numbers;
    private final SalesAuditPort audit;
    private final Clock clock;
    private final TenantDirectoryPort tenants;
    private final SalesReturnRepository returns;

    public CommitPosOrderUseCase(SalesOrderRepository orders, StockService stockService,
                                 GetSalesSettingsUseCase settingsUseCase, DocumentNumberPort numbers,
                                 SalesAuditPort audit, Clock clock, TenantDirectoryPort tenants,
                                 SalesReturnRepository returns) {
        this.tenants = tenants;
        this.returns = returns;
        this.orders = orders;
        this.stockService = stockService;
        this.settingsUseCase = settingsUseCase;
        this.numbers = numbers;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public OrderResponse execute(PlacePosOrderCommand cmd, Map<UUID, CatalogTitle> titles) {
        var existing = orders.findByClientRequestId(cmd.tenantId(), cmd.clientRequestId());
        if (existing.isPresent()) {
            return OrderResponse.from(existing.get());
        }
        if (cmd.exchangeOfReturnId() != null && returns.findById(cmd.tenantId(), cmd.exchangeOfReturnId()).isEmpty()) {
            throw new SalesException(ErrorCode.RETURN_NOT_FOUND, "Không có phiếu trả để đổi hàng");
        }
        SalesSettingsView settings = settingsUseCase.execute(cmd.tenantId());
        Map<UUID, SellableItem> items = stockService.sellableItems(cmd.tenantId(),
                cmd.lines().stream().map(PlacePosOrderCommand.Line::titleId).distinct().toList());

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
                    line.discount()));
        }
        var now = clock.instant();
        SalesOrder order = SalesOrder.createPos(cmd.tenantId(), numbers.next(cmd.tenantId(),
                        DocumentNumberPort.ORDER), cmd.userId(), now, cmd.paymentMethod(), cmd.customerUserId(),
                cmd.walkInName(), cmd.walkInPhone(), drafts, cmd.orderDiscount(),
                new PriceCalculator.TaxPolicy(settings.vatRate(), settings.pricesIncludeVat()),
                cmd.clientRequestId(), cmd.exchangeOfReturnId());

        stockService.sellAll(cmd.tenantId(), cmd.userId(),
                cmd.lines().stream().map(l -> new StockLine(l.titleId(), l.quantity())).toList(), "ORDER",
                order.id());
        SalesOrder saved = orders.save(order);
        tenants.touch(cmd.tenantId());

        audit.record(new SalesAuditPort.AuditEntry(cmd.tenantId(), SalesAuditPort.Actions.ORDER_CREATED, "SalesOrder",
                saved.id(), cmd.userId(), now,
                Map.of("orderNo", saved.orderNo(), "channel", saved.channel().name(),
                        "paymentMethod", saved.paymentMethod().name(), "lines", saved.lines().size(),
                        "grandTotal", saved.grandTotal().amount(), "discountTotal", saved.discountTotal().amount(),
                        "exchangeOfReturnId", String.valueOf(saved.exchangeOfReturnId()))));
        if (saved.discountTotal().amount() > 0) {
            audit.record(new SalesAuditPort.AuditEntry(cmd.tenantId(), SalesAuditPort.Actions.ORDER_DISCOUNT_APPLIED,
                    "SalesOrder", saved.id(), cmd.userId(), now,
                    Map.of("discountTotal", saved.discountTotal().amount(),
                            "subtotal", saved.subtotal().amount())));
        }
        return OrderResponse.from(saved);
    }
}
