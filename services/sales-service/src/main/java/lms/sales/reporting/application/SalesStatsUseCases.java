package lms.sales.reporting.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.sales.reporting.application.SalesStatsQueryPort.SoldTitle;
import lms.sales.reporting.domain.ReportRange;
import lms.sales.reporting.domain.RevenueTrend;
import lms.sales.settings.application.GetSalesSettingsUseCase;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.application.CatalogLookupPort;
import lms.sales.stock.application.CatalogLookupPort.CatalogTitle;
import lms.sales.stock.application.StockPort;
import lms.sales.stock.domain.StockItem;

/**
 * Số liệu dashboard bán hàng (ADR-0018). Tồn thấp dùng đúng định nghĩa của lowStockOnly (StockPort), doanh thu theo
 * dòng tiền (xem {@link RevenueTrend}). Không mở transaction ở đây: mỗi lần đọc là một transaction chỉ đọc của
 * adapter; tra catalog (HTTP) nằm ngoài transaction.
 */
public class SalesStatsUseCases {

    public static final int LOW_STOCK_ITEMS = 10;
    public static final int DEFAULT_TOP = 10;
    public static final int MAX_TOP = 50;

    public record LowStockItem(UUID titleId, String titleName, long available, int threshold) {
    }

    public record Summary(LocalDate asOf, long ordersToday, long grossToday, long refundsToday, long revenueToday,
                          long pendingOnlineOrders, long lowStockCount, List<LowStockItem> lowStockItems) {
    }

    private final SalesStatsQueryPort query;
    private final StockPort stock;
    private final GetSalesSettingsUseCase settings;
    private final CatalogLookupPort catalog;
    private final Clock clock;

    public SalesStatsUseCases(SalesStatsQueryPort query, StockPort stock, GetSalesSettingsUseCase settings,
                              CatalogLookupPort catalog, Clock clock) {
        this.query = query;
        this.stock = stock;
        this.settings = settings;
        this.catalog = catalog;
        this.clock = clock;
    }

    /** asOf null = hôm nay giờ VN. */
    public Summary summary(UUID tenantId, LocalDate asOf) {
        LocalDate day = asOf != null ? asOf : today();
        ReportRange today = ReportRange.singleDay(day);
        RevenueTrend.Totals t = RevenueTrend.build(today, query.grossByDay(tenantId, today),
                query.refundsByDay(tenantId, today)).totals();
        int threshold = settings.execute(tenantId).lowStockThreshold();
        var low = stock.listForReport(tenantId, true, threshold, 0, LOW_STOCK_ITEMS);
        Map<UUID, CatalogTitle> names = lookupNames(low.items().stream().map(StockItem::titleId).toList());
        List<LowStockItem> items = low.items().stream().limit(LOW_STOCK_ITEMS).map(i -> new LowStockItem(i.titleId(),
                names.containsKey(i.titleId()) ? names.get(i.titleId()).name() : null, i.available(),
                i.lowStockThreshold() != null ? i.lowStockThreshold() : threshold)).toList();
        return new Summary(day, t.orders(), t.gross(), t.refunds(), t.net(),
                query.countPendingOnlineOrders(tenantId), low.total(), items);
    }

    public RevenueTrend revenueTrend(UUID tenantId, LocalDate from, LocalDate to) {
        ReportRange range = ReportRange.of(from, to, today());
        return RevenueTrend.build(range, query.grossByDay(tenantId, range), query.refundsByDay(tenantId, range));
    }

    public List<SoldTitle> topSold(UUID tenantId, LocalDate from, LocalDate to, Integer limit) {
        int n = limit == null ? DEFAULT_TOP : limit;
        if (n < 1 || n > MAX_TOP) {
            throw SalesException.invalidRequest("limit phải từ 1 đến " + MAX_TOP);
        }
        return query.topSold(tenantId, ReportRange.of(from, to, today()), n);
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ReportRange.ZONE));
    }

    /** Tên là phần bổ sung: catalog lỗi thì để trống, không làm hỏng số liệu. */
    private Map<UUID, CatalogTitle> lookupNames(List<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            return catalog.lookup(ids);
        } catch (SalesException e) {
            return Map.of();
        }
    }
}
