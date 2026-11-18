package lms.sales.ordering.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.sales.ordering.domain.PaymentIntent;
import lms.sales.ordering.domain.PaymentMethod;
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
import lms.sales.stock.application.SellableItem;
import lms.sales.stock.application.StockLine;
import lms.sales.stock.application.StockService;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phần transactional của đơn POS HAI BƯỚC (ADR-0020, ADR-0021) cho mọi phương thức: giá, tính tiền, GIỮ tồn (reserved,
 * như đơn online), lưu đơn AWAITING_PAYMENT và lệnh thanh toán PENDING, audit, tất cả trong một transaction của sales_db.
 * QR: lệnh của cổng ({@code gateway.provider()}, có qrPayload). CASH, CARD, BANK_TRANSFER: lệnh MANUAL (không có
 * qrPayload), thu ngân xác nhận bằng {@link PaymentIntentUseCases#confirm}. Hết hàng thì rollback cả đơn. Tồn chỉ bị trừ
 * thật (FULFIL) khi thu xong, hoặc được nhả khi hết hạn/hủy. Ngoại lệ: tổng tiền 0 (giảm giá hết) không có gì để chờ,
 * đơn thu ngay như cũ (trừ tồn trực tiếp, không có lệnh).
 */
public class CommitPosPendingOrderUseCase {

    private final SalesOrderRepository orders;
    private final PaymentIntentRepository intents;
    /** Các cổng đang bật (QR giả lập, MoMo); thiếu cổng của phương thức thì đơn bị từ chối 400 PAYMENT_NOT_ENABLED. */
    private final List<PaymentGatewayPort> gateways;
    private final StockService stockService;
    private final GetSalesSettingsUseCase settingsUseCase;
    private final DocumentNumberPort numbers;
    private final SalesAuditPort audit;
    private final TenantDirectoryPort tenants;
    private final SalesReturnRepository returns;
    private final Clock clock;
    private final Duration qrTtl;
    private final Duration manualTtl;

    public CommitPosPendingOrderUseCase(SalesOrderRepository orders, PaymentIntentRepository intents,
                                        List<PaymentGatewayPort> gateways, StockService stockService,
                                        GetSalesSettingsUseCase settingsUseCase, DocumentNumberPort numbers,
                                        SalesAuditPort audit, TenantDirectoryPort tenants,
                                        SalesReturnRepository returns, Clock clock, Duration qrTtl,
                                        Duration manualTtl) {
        this.orders = orders;
        this.intents = intents;
        this.gateways = gateways;
        this.stockService = stockService;
        this.settingsUseCase = settingsUseCase;
        this.numbers = numbers;
        this.audit = audit;
        this.tenants = tenants;
        this.returns = returns;
        this.clock = clock;
        this.qrTtl = qrTtl;
        this.manualTtl = manualTtl;
    }

    /** Đơn đã tạo trước đó (gửi lại cùng clientRequestId): trả đơn kèm lệnh thanh toán hiện có (nếu có). */
    @Transactional(readOnly = true)
    public PosOrderResult replay(UUID tenantId, SalesOrder existing) {
        return new PosOrderResult(OrderResponse.from(existing), intents.findByOrderId(tenantId, existing.id())
                .map(i -> PaymentIntentResponse.from(i, clock.instant())).orElse(null));
    }

    @Transactional
    public PosOrderResult execute(PlacePosOrderCommand cmd, Map<UUID, CatalogTitle> titles) {
        var existing = orders.findByClientRequestId(cmd.tenantId(), cmd.clientRequestId());
        if (existing.isPresent()) {
            return replay(cmd.tenantId(), existing.get());
        }
        boolean viaGateway = cmd.paymentMethod().isGateway();
        PaymentGatewayPort gateway = viaGateway ? gatewayFor(cmd.paymentMethod()) : null;
        if (viaGateway && gateway == null) {
            throw new SalesException(ErrorCode.PAYMENT_NOT_ENABLED, cmd.paymentMethod() == PaymentMethod.MOMO
                    ? "Thanh toán MoMo chưa được bật" : "Thanh toán QR chưa được bật");
        }
        if (cmd.exchangeOfReturnId() != null
                && returns.findById(cmd.tenantId(), cmd.exchangeOfReturnId()).isEmpty()) {
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
        Instant now = clock.instant();
        SalesOrder order = SalesOrder.createPosAwaitingPayment(cmd.tenantId(), numbers.next(cmd.tenantId(),
                        DocumentNumberPort.ORDER), cmd.userId(), now, cmd.paymentMethod(), cmd.customerUserId(),
                cmd.walkInName(), cmd.walkInPhone(), drafts, cmd.orderDiscount(),
                new PriceCalculator.TaxPolicy(settings.vatRate(), settings.pricesIncludeVat()),
                cmd.clientRequestId(), cmd.exchangeOfReturnId(), cmd.customerNote());
        List<StockLine> stockLines = cmd.lines().stream().map(l -> new StockLine(l.titleId(), l.quantity())).toList();

        if (order.grandTotal().isZero()) {
            // Không có gì để chờ: thu ngay (không lệnh thanh toán), trừ tồn trực tiếp như đơn POS cũ
            order.settleAwaitingPayment(null, cmd.userId(), now);
            stockService.sellAll(cmd.tenantId(), cmd.userId(), stockLines, "ORDER", order.id());
            SalesOrder saved = orders.save(order);
            tenants.touch(cmd.tenantId());
            audit(cmd, saved, now, null);
            return new PosOrderResult(OrderResponse.from(saved), null);
        }

        stockService.reserveAll(cmd.tenantId(), cmd.userId(), stockLines, "ORDER", order.id());
        SalesOrder saved = orders.save(order);

        String reference = PaymentReferences.next();
        String provider = PaymentIntent.PROVIDER_MANUAL;
        String qrPayload = null;
        Instant expiresAt = now.plus(manualTtl);
        if (viaGateway) {
            expiresAt = now.plus(qrTtl);
            provider = gateway.provider();
            qrPayload = gateway.createQr(new PaymentGatewayPort.ChargeRequest(reference, saved.grandTotal(),
                    expiresAt)).qrPayload();
        }
        PaymentIntent intent = intents.save(PaymentIntent.create(cmd.tenantId(), saved.id(), saved.grandTotal(),
                reference, provider, qrPayload, now, expiresAt));
        tenants.touch(cmd.tenantId());
        audit(cmd, saved, now, intent);
        return new PosOrderResult(OrderResponse.from(saved), PaymentIntentResponse.from(intent, now));
    }

    private PaymentGatewayPort gatewayFor(PaymentMethod method) {
        String provider = method == PaymentMethod.MOMO ? PaymentIntent.PROVIDER_MOMO : PaymentIntent.PROVIDER_SIMULATED;
        return gateways.stream().filter(g -> provider.equals(g.provider())).findFirst().orElse(null);
    }

    private void audit(PlacePosOrderCommand cmd, SalesOrder saved, Instant now, PaymentIntent intent) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("orderNo", saved.orderNo());
        data.put("channel", saved.channel().name());
        data.put("paymentMethod", saved.paymentMethod().name());
        data.put("lines", saved.lines().size());
        data.put("grandTotal", saved.grandTotal().amount());
        data.put("discountTotal", saved.discountTotal().amount());
        data.put("exchangeOfReturnId", String.valueOf(saved.exchangeOfReturnId()));
        if (intent != null) {
            data.put("paymentIntentId", intent.id().toString());
            data.put("paymentReference", intent.reference());
            data.put("provider", intent.provider());
        }
        audit.record(new SalesAuditPort.AuditEntry(cmd.tenantId(), SalesAuditPort.Actions.ORDER_CREATED, "SalesOrder",
                saved.id(), cmd.userId(), now, data));
        if (saved.discountTotal().amount() > 0) {
            audit.record(new SalesAuditPort.AuditEntry(cmd.tenantId(), SalesAuditPort.Actions.ORDER_DISCOUNT_APPLIED,
                    "SalesOrder", saved.id(), cmd.userId(), now,
                    Map.of("discountTotal", saved.discountTotal().amount(),
                            "subtotal", saved.subtotal().amount())));
        }
    }
}
