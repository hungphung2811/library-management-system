package lms.sales.ordering.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.sales.ordering.domain.PaymentIntent;
import lms.sales.ordering.domain.PaymentMethod;
import lms.sales.ordering.domain.PriceCalculator;
import lms.sales.ordering.domain.SalesOrder;
import lms.sales.settings.application.GetSalesSettingsUseCase;
import lms.sales.settings.application.OrderAbuseLimits;
import lms.sales.settings.application.OrderAbusePolicyUseCases;
import lms.sales.settings.application.SalesSettingsView;
import lms.sales.shared.application.DocumentNumberPort;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.application.TenantDirectoryPort;
import lms.sales.shared.domain.ErrorCode;
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
    private final OrderAbusePolicyUseCases policies;
    private final CustomerOrderLockPort customerLock;
    private final AntiAbuseService antiAbuse;
    /** Thanh toán trực tuyến (ADR-0029): kho lệnh, các cổng đang bật, cờ mở SEPAY cho website và hạn lệnh/giữ tồn. */
    private final PaymentIntentRepository intents;
    private final List<PaymentGatewayPort> gateways;
    private final boolean sepayOnlineEnabled;
    private final Duration onlineIntentTtl;

    /** Không bật thanh toán trực tuyến: đơn SEPAY bị từ chối 400 PAYMENT_NOT_ENABLED. */
    public CommitOnlineOrderUseCase(SalesOrderRepository orders, StockService stockService,
                                    GetSalesSettingsUseCase settingsUseCase, DocumentNumberPort numbers,
                                    SalesAuditPort audit, TenantDirectoryPort tenants, Clock clock,
                                    OrderAbusePolicyUseCases policies, CustomerOrderLockPort customerLock,
                                    AntiAbuseService antiAbuse) {
        this(orders, stockService, settingsUseCase, numbers, audit, tenants, clock, policies, customerLock, antiAbuse,
                null, List.of(), false, Duration.ofMinutes(15));
    }

    public CommitOnlineOrderUseCase(SalesOrderRepository orders, StockService stockService,
                                    GetSalesSettingsUseCase settingsUseCase, DocumentNumberPort numbers,
                                    SalesAuditPort audit, TenantDirectoryPort tenants, Clock clock,
                                    OrderAbusePolicyUseCases policies, CustomerOrderLockPort customerLock,
                                    AntiAbuseService antiAbuse, PaymentIntentRepository intents,
                                    List<PaymentGatewayPort> gateways, boolean sepayOnlineEnabled,
                                    Duration onlineIntentTtl) {
        this.intents = intents;
        this.gateways = gateways;
        this.sepayOnlineEnabled = sepayOnlineEnabled;
        this.onlineIntentTtl = onlineIntentTtl;
        this.antiAbuse = antiAbuse;
        this.policies = policies;
        this.customerLock = customerLock;
        this.orders = orders;
        this.stockService = stockService;
        this.settingsUseCase = settingsUseCase;
        this.numbers = numbers;
        this.audit = audit;
        this.tenants = tenants;
        this.clock = clock;
    }

    /**
     * Phương thức thanh toán có đặt được ở kênh online không (kiểm sớm, trước khi tra catalog). Cổng chỉ cho SEPAY
     * (danh sách tường minh): QR/MoMo bị 400 INVALID_REQUEST; SEPAY mà cờ website tắt hoặc chưa có cổng thì
     * 400 PAYMENT_NOT_ENABLED. CARD và các phương thức khác do domain từ chối như trước.
     */
    public void requirePaymentMethodAvailable(PaymentMethod method) {
        if (method == null || !method.isGateway()) {
            return;
        }
        if (!method.allowedOnlineGateway()) {
            throw SalesException.invalidRequest("Phương thức thanh toán không hợp lệ cho đơn online");
        }
        if (!sepayOnlineEnabled || intents == null || sepayGateway() == null) {
            throw new SalesException(ErrorCode.PAYMENT_NOT_ENABLED, "Thanh toán SePay chưa được bật");
        }
    }

    private PaymentGatewayPort sepayGateway() {
        return gateways.stream().filter(g -> PaymentIntent.PROVIDER_SEPAY.equals(g.provider())).findFirst()
                .orElse(null);
    }

    @Transactional
    public ShopOrderResponse execute(PlaceOnlineOrderCommand cmd, Map<UUID, CatalogTitle> titles) {
        // Tuần tự hóa các lần đặt đơn của CÙNG khách: trần số đơn không bị vượt khi nhiều request đến cùng lúc, và hai
        // request trùng clientRequestId thì request sau thấy đơn của request trước (phát lại) thay vì đụng khóa duy nhất.
        customerLock.lock(cmd.tenantId(), cmd.customerUserId());
        var existing = orders.findByClientRequestId(cmd.tenantId(), cmd.clientRequestId());
        if (existing.isPresent()) {
            return PlaceOnlineOrderUseCase.replay(cmd, existing.get());
        }
        requirePaymentMethodAvailable(cmd.paymentMethod());
        OrderAbuseLimits policy = policies.current(cmd.tenantId());
        AntiAbuseService.RiskLevel risk = checkOrderCounts(cmd, policy);
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
        var tax = new PriceCalculator.TaxPolicy(settings.vatRate(), settings.pricesIncludeVat());
        String orderNo = numbers.next(cmd.tenantId(), DocumentNumberPort.ORDER);
        List<StockLine> stockLines = cmd.lines().stream().map(l -> new StockLine(l.titleId(), l.quantity())).toList();
        boolean viaGateway = cmd.paymentMethod() != null && cmd.paymentMethod().isGateway();
        SalesOrder order;
        if (viaGateway) {
            // Trả trước qua SePay (ADR-0029): AWAITING_PAYMENT, phí ship cố định của tenant chốt ngay vào tổng tiền;
            // hạn giữ hàng do lệnh thanh toán quyết định (reservationExpiresAt null)
            order = SalesOrder.placeOnlineAwaitingPayment(cmd.tenantId(), orderNo, cmd.customerUserId(), now,
                    cmd.paymentMethod(), cmd.fulfilment(), cmd.recipient(), drafts, tax, cmd.clientRequestId(),
                    cmd.customerNote(), settings.fixedShippingMoney(), OrderRequestFingerprint.of(cmd));
        } else {
            order = SalesOrder.placeOnline(cmd.tenantId(), orderNo, cmd.customerUserId(), now, cmd.paymentMethod(),
                    cmd.fulfilment(), cmd.recipient(), drafts, tax, cmd.clientRequestId(), cmd.customerNote());
            // Hạn giữ hàng (0 phút = không hết hạn) và mã băm nội dung để phát hiện cùng khóa mà khác nội dung
            order.placeHold(policy.reservationMinutes() == 0 ? null
                    : now.plus(Duration.ofMinutes(policy.reservationMinutes())), OrderRequestFingerprint.of(cmd));
        }

        // Giữ tồn trong cùng transaction: hết hàng thì ném INSUFFICIENT_STOCK và rollback cả đơn
        stockService.reserveAll(cmd.tenantId(), cmd.customerUserId(), stockLines, "ORDER", order.id());
        SalesOrder saved = orders.save(order);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderNo", saved.orderNo());
        data.put("channel", saved.channel().name());
        data.put("paymentMethod", saved.paymentMethod().name());
        data.put("fulfilment", saved.fulfilment().name());
        data.put("lines", saved.lines().size());
        data.put("grandTotal", saved.grandTotal().amount());
        data.put("riskLevel", risk.name());
        if (viaGateway) {
            // createQr của SePay không gọi mạng nên tạo lệnh được trong cùng transaction (không giữ HTTP trong tx)
            PaymentGatewayPort gateway = sepayGateway();
            String reference = PaymentReferences.next();
            Instant expiresAt = now.plus(onlineIntentTtl);
            String qrPayload = gateway.createQr(new PaymentGatewayPort.ChargeRequest(reference, saved.grandTotal(),
                    expiresAt)).qrPayload();
            PaymentIntent intent = intents.save(PaymentIntent.create(cmd.tenantId(), saved.id(), saved.grandTotal(),
                    reference, gateway.provider(), qrPayload, now, expiresAt));
            data.put("paymentIntentId", intent.id().toString());
            data.put("paymentReference", intent.reference());
            data.put("provider", intent.provider());
        }
        tenants.touch(cmd.tenantId());
        audit.record(new SalesAuditPort.AuditEntry(cmd.tenantId(), SalesAuditPort.Actions.ORDER_CREATED, "SalesOrder",
                saved.id(), cmd.customerUserId(), now, data));
        return ShopOrderResponse.from(saved);
    }

    /**
     * Chấm rủi ro rồi kiểm trần (đã giữ khóa khách nên số đếm không bị vượt đồng thời). CRITICAL bị chặn, HIGH bị siết
     * trần đơn chờ còn một nửa. Trả mức rủi ro để ghi audit.
     */
    private AntiAbuseService.RiskLevel checkOrderCounts(PlaceOnlineOrderCommand cmd, OrderAbuseLimits policy) {
        var now = clock.instant();
        long pending = orders.countPendingOnline(cmd.tenantId(), cmd.customerUserId());
        long day = orders.countOnlineSince(cmd.tenantId(), cmd.customerUserId(), now.minus(Duration.ofHours(24)));
        long month = orders.countOnlineSince(cmd.tenantId(), cmd.customerUserId(), now.minus(Duration.ofDays(30)));
        long cancelled = orders.countCancelledOnlineSince(cmd.tenantId(), cmd.customerUserId(),
                now.minus(Duration.ofDays(30)));
        long quantity = cmd.lines().stream().mapToLong(PlaceOnlineOrderCommand.Line::quantity).sum();
        var assessment = antiAbuse.assess(new AntiAbuseService.Signals(month, cancelled, day, pending, quantity),
                policy);
        if (assessment.blocked()) {
            throw new SalesException(ErrorCode.ORDER_LIMIT_EXCEEDED,
                    "Tài khoản tạm thời không thể đặt thêm đơn, vui lòng liên hệ cửa hàng");
        }
        if (pending >= assessment.effectiveMaxPending()) {
            throw new SalesException(ErrorCode.ORDER_LIMIT_EXCEEDED,
                    "Bạn đang có " + pending + " đơn chờ xác nhận, vui lòng đợi hoặc hủy bớt");
        }
        if (day >= policy.maxOrdersPerDay()) {
            throw new SalesException(ErrorCode.ORDER_LIMIT_EXCEEDED,
                    "Bạn đã đặt tối đa " + policy.maxOrdersPerDay() + " đơn trong 24 giờ, vui lòng thử lại sau");
        }
        return assessment.level();
    }
}
