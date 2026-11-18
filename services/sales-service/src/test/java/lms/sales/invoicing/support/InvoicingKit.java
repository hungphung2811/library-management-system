package lms.sales.invoicing.support;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lms.sales.invoicing.application.AdjustInvoiceForReturnUseCase;
import lms.sales.invoicing.application.AutoIssueInvoiceUseCase;
import lms.sales.invoicing.application.InternalInvoiceIssuer;
import lms.sales.invoicing.application.InvoiceIssuerPort;
import lms.sales.invoicing.application.InvoiceQueryUseCases;
import lms.sales.invoicing.application.InvoiceRepository;
import lms.sales.invoicing.application.InvoiceRequestRepository;
import lms.sales.invoicing.application.InvoiceRequestUseCases;
import lms.sales.invoicing.application.InvoiceSettingsPort;
import lms.sales.invoicing.application.InvoiceSettingsUseCases;
import lms.sales.invoicing.application.InvoiceSummary;
import lms.sales.invoicing.application.InvoiceUseCases;
import lms.sales.invoicing.domain.Invoice;
import lms.sales.invoicing.domain.InvoiceNumbers;
import lms.sales.invoicing.domain.InvoiceRequest;
import lms.sales.invoicing.domain.InvoiceSettings;
import lms.sales.invoicing.infrastructure.print.HtmlInvoiceRenderer;
import lms.sales.ordering.application.CommitReturnUseCase;
import lms.sales.ordering.application.CreateReturnUseCase;
import lms.sales.ordering.infrastructure.invoicing.InvoiceOrderSourceAdapter;
import lms.sales.shared.application.DocumentNumberPort;
import lms.sales.shared.application.PageResult;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.support.Harness;

/**
 * Bộ dựng test cho context invoicing trên nền {@link Harness} (đơn, trả hàng, tồn kho giả trong bộ nhớ): kho hóa đơn,
 * yêu cầu, cấu hình giả; cài đặt cổng đọc đơn THẬT của ordering; use case thật. Bộ đếm số hóa đơn và audit riêng, an toàn
 * đa luồng (cho test phát hành song song). Không thay thế IT Postgres: không có khóa dòng, RLS hay trigger.
 */
public final class InvoicingKit {

    public final Harness h;
    public final Invoices invoices = new Invoices();
    public final Requests requests = new Requests();
    public final Settings settings = new Settings();
    public final List<SalesAuditPort.AuditEntry> audits = Collections.synchronizedList(new ArrayList<>());
    public final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();
    public final List<String> counterNames = Collections.synchronizedList(new ArrayList<>());
    public final DocumentNumberPort numbers = new DocumentNumberPort() {
        @Override
        public String next(UUID tenantId, String sequence) {
            return sequence + "-" + nextValue(tenantId, sequence);
        }

        @Override
        public long nextValue(UUID tenantId, String sequence) {
            counterNames.add(sequence);
            return counters.computeIfAbsent(sequence, k -> new AtomicLong()).incrementAndGet();
        }
    };
    public final SalesAuditPort audit = audits::add;
    public final InvoiceOrderSourceAdapter source;
    public final InvoiceIssuerPort issuer = new InternalInvoiceIssuer(numbers);
    public final InvoiceUseCases useCases;
    public final AdjustInvoiceForReturnUseCase adjust;
    public final AutoIssueInvoiceUseCase autoIssue;
    public final InvoiceQueryUseCases queries;
    public final InvoiceRequestUseCases requestUseCases;
    public final InvoiceSettingsUseCases settingsUseCases;
    public final CommitReturnUseCase commitReturn;
    public final CreateReturnUseCase createReturn;

    public InvoicingKit(Harness h) {
        this.h = h;
        Clock clock = h.clock;
        this.source = new InvoiceOrderSourceAdapter(h.orderRepo, h.returnRepo);
        this.useCases = new InvoiceUseCases(source, invoices, requests, settings, issuer, audit, clock);
        this.adjust = new AdjustInvoiceForReturnUseCase(invoices, issuer, audit);
        this.autoIssue = new AutoIssueInvoiceUseCase(settings, requests, invoices, source, useCases);
        this.queries = new InvoiceQueryUseCases(invoices, new HtmlInvoiceRenderer());
        this.requestUseCases = new InvoiceRequestUseCases(source, requests, autoIssue, audit, clock);
        this.settingsUseCases = new InvoiceSettingsUseCases(settings, audit, clock);
        this.commitReturn = new CommitReturnUseCase(h.orderRepo, h.returnRepo, h.refundRepo, h.stockService,
                h.getSettings, h.numbers, h.auditPort, clock).withInvoiceAdjustment(adjust);
        this.createReturn = new CreateReturnUseCase(commitReturn, h.returnRepo);
    }

    /** Cấu hình người bán đủ để phát hành (mẫu số 1, ký hiệu C26TAA). */
    public InvoiceSettings configureSeller(boolean autoIssueOnCompletion) {
        return configureSeller(h.tenant, autoIssueOnCompletion);
    }

    public InvoiceSettings configureSeller(UUID tenant, boolean autoIssueOnCompletion) {
        return settings.save(InvoiceSettings.defaults(tenant).update("Nhà sách Demo", "0123456789",
                "1 Lê Lợi, Quận 1, TP.HCM", "0900000000", null, "1", "C26TAA", autoIssueOnCompletion,
                h.clock.instant(), h.user));
    }

    public List<SalesAuditPort.AuditEntry> auditsOf(String action) {
        synchronized (audits) {
            return audits.stream().filter(a -> a.action().equals(action)).toList();
        }
    }

    // ---- kho hóa đơn giả ----

    /** Giả lập chỉ mục duy nhất của DB: một hóa đơn gốc/thay thế còn hiệu lực mỗi đơn, một điều chỉnh mỗi phiếu trả, số không trùng. */
    public static final class Invoices implements InvoiceRepository {
        private final Map<UUID, Invoice> byId = new LinkedHashMap<>();

        @Override
        public synchronized Invoice insert(Invoice i) {
            if (i.kind().isPrimary() && i.status() != lms.sales.invoicing.domain.InvoiceStatus.CANCELLED
                    && byId.values().stream().anyMatch(o -> o.kind().isPrimary() && o.orderId().equals(i.orderId())
                    && o.status() != lms.sales.invoicing.domain.InvoiceStatus.CANCELLED)) {
                throw new SalesException(ErrorCode.INVOICE_ALREADY_EXISTS, "trùng hóa đơn hiệu lực của đơn");
            }
            if (i.returnId() != null && byId.values().stream().anyMatch(o -> i.returnId().equals(o.returnId())
                    && o.status() != lms.sales.invoicing.domain.InvoiceStatus.CANCELLED)) {
                throw new SalesException(ErrorCode.INVOICE_ALREADY_EXISTS, "trùng hóa đơn điều chỉnh của phiếu trả");
            }
            if (i.invoiceNo() != null && byId.values().stream().anyMatch(o -> o.invoiceNo() != null
                    && o.invoiceNo().equals(i.invoiceNo()) && o.series().equals(i.series())
                    && o.templateCode().equals(i.templateCode()) && o.fiscalYear().equals(i.fiscalYear()))) {
                throw new IllegalStateException("Trùng số hóa đơn " + i.invoiceNo());
            }
            byId.put(i.id(), i);
            return i;
        }

        @Override
        public synchronized Invoice update(Invoice i) {
            byId.put(i.id(), i);
            return i;
        }

        @Override
        public synchronized Optional<Invoice> findById(UUID tenantId, UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public synchronized Optional<Invoice> findByClientRequestId(UUID tenantId, UUID c) {
            return byId.values().stream().filter(i -> c.equals(i.clientRequestId())).findFirst();
        }

        @Override
        public synchronized Optional<Invoice> findEffectiveForOrder(UUID tenantId, UUID orderId) {
            return byId.values().stream().filter(i -> i.kind().isPrimary() && i.orderId().equals(orderId)
                    && i.isEffective()).findFirst();
        }

        @Override
        public synchronized List<Invoice> findActiveAdjustments(UUID tenantId, UUID originalId) {
            return byId.values().stream().filter(i -> originalId.equals(i.originalInvoiceId()) && !i.kind().isPrimary()
                    && i.isEffective()).sorted(Comparator.comparing(Invoice::createdAt)).toList();
        }

        @Override
        public synchronized Optional<Invoice> findAdjustmentByReturn(UUID tenantId, UUID returnId) {
            return byId.values().stream().filter(i -> returnId.equals(i.returnId())
                    && i.status() != lms.sales.invoicing.domain.InvoiceStatus.CANCELLED).findFirst();
        }

        @Override
        public synchronized PageResult<InvoiceSummary> search(UUID tenantId, Criteria c, int page, int size) {
            List<InvoiceSummary> all = byId.values().stream()
                    .filter(i -> c.status() == null || i.status() == c.status())
                    .filter(i -> c.kind() == null || i.kind() == c.kind())
                    .filter(i -> c.keyword() == null || i.orderNo().toLowerCase().contains(c.keyword().toLowerCase()))
                    .map(i -> new InvoiceSummary(i.id(), i.kind().name(), i.status().name(), i.orderId(), i.orderNo(),
                            i.originalInvoiceId(), i.templateCode(), i.series(), i.fiscalYear(), i.invoiceNo(),
                            i.displayNumber(), i.buyer().name(), i.buyer().company(), i.buyer().taxCode(),
                            i.grandTotal().amount(), i.issuedAt(), i.createdAt())).toList();
            int from = Math.min(page * size, all.size());
            return new PageResult<>(all.subList(from, Math.min(from + size, all.size())), all.size(), page, size);
        }

        public synchronized List<Invoice> all() {
            return List.copyOf(byId.values());
        }
    }

    public static final class Requests implements InvoiceRequestRepository {
        private final Map<UUID, InvoiceRequest> byOrder = new ConcurrentHashMap<>();

        @Override
        public Optional<InvoiceRequest> findByOrder(UUID tenantId, UUID orderId) {
            return Optional.ofNullable(byOrder.get(orderId));
        }

        @Override
        public InvoiceRequest save(InvoiceRequest r) {
            byOrder.put(r.orderId(), r);
            return r;
        }
    }

    public static final class Settings implements InvoiceSettingsPort {
        private final Map<UUID, InvoiceSettings> byTenant = new ConcurrentHashMap<>();

        @Override
        public Optional<InvoiceSettings> find(UUID tenantId) {
            return Optional.ofNullable(byTenant.get(tenantId));
        }

        @Override
        public InvoiceSettings save(InvoiceSettings s) {
            InvoiceSettings stored = InvoiceSettings.restore(s.tenantId(), s.sellerLegalName(), s.sellerTaxCode(),
                    s.sellerAddress(), s.sellerPhone(), s.sellerEmail(), s.templateCode(), s.series(),
                    s.autoIssueOnCompletion(), s.version() + 1, s.updatedAt(), s.updatedBy());
            byTenant.put(s.tenantId(), stored);
            return stored;
        }
    }

    /** Năm tài chính (giờ VN) của thời điểm hiện tại của đồng hồ giả. */
    public int year() {
        return InvoiceNumbers.fiscalYear(h.clock.instant());
    }
}
