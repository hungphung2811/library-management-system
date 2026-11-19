package lms.sales.invoicing.infrastructure.demo;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lms.demo.plan.DemoIds;
import lms.demo.plan.DemoPlan;
import lms.demo.plan.DemoPlan.OrderPlan;
import lms.demo.runner.DemoSeedContext;
import lms.demo.runner.DemoSeedStep;
import lms.sales.invoicing.application.BuyerInput;
import lms.sales.invoicing.application.InvoiceRepository;
import lms.sales.invoicing.application.InvoiceRequestRepository;
import lms.sales.invoicing.application.InvoiceRequestUseCases;
import lms.sales.invoicing.application.InvoiceSettingsPort;
import lms.sales.invoicing.application.InvoiceSettingsUseCases;
import lms.sales.invoicing.application.InvoiceUseCases;
import lms.sales.invoicing.application.InvoiceView;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Seed hóa đơn nội bộ của tenant demo (ADR-0030 gói D, ADR-0018): cấu hình người bán, yêu cầu xuất hóa đơn (công ty có
 * MST), hóa đơn đã phát hành, một hóa đơn đã hủy, một hóa đơn đã thay thế, và vài yêu cầu còn mở (chưa lập). Dùng chính
 * use case thật của invoicing (cấp số, audit, khóa đơn) trên các đơn đã hoàn tất và đã thu tiền, chưa có phiếu trả, nên
 * số liệu khớp đơn. Thời điểm phát hành là lúc chạy seed (use case dùng đồng hồ hệ thống).
 * <p>
 * Idempotent: cấu hình chỉ ghi khi chưa có; yêu cầu chỉ tạo khi đơn chưa có; hóa đơn phát lại theo
 * {@code clientRequestId} tất định (trả hóa đơn cũ), hủy/thay thế chỉ chạy khi hóa đơn gốc còn ISSUED.
 */
@Component
@Profile("demo-seed")
public class InvoicingDemoSeedStep implements DemoSeedStep {

    static final String SELLER_NAME = "Công ty TNHH Thư viện Demo";
    static final String SELLER_TAX_CODE = "0101234567";
    static final String SERIES = "C26TDM";
    static final int MAX_ORDERS = 14;
    /** Số hóa đơn gốc lập (k nhỏ hơn mốc này), hủy ở vị trí CANCEL_AT, thay thế ở REPLACE_AT; từ OPEN_FROM chỉ có yêu cầu. */
    static final int ISSUE_BELOW = 8;
    static final int CANCEL_AT = 3;
    static final int REPLACE_AT = 5;
    static final int OPEN_FROM = 8;
    static final int OPEN_TO = 11;

    private final InvoiceSettingsPort settingsPort;
    private final InvoiceSettingsUseCases settings;
    private final InvoiceUseCases invoices;
    private final InvoiceRequestUseCases requestUseCases;
    private final InvoiceRequestRepository requests;
    private final InvoiceRepository invoiceRepository;

    public InvoicingDemoSeedStep(InvoiceSettingsPort settingsPort, InvoiceSettingsUseCases settings,
                                 InvoiceUseCases invoices, InvoiceRequestUseCases requestUseCases,
                                 InvoiceRequestRepository requests, InvoiceRepository invoiceRepository) {
        this.settingsPort = settingsPort;
        this.settings = settings;
        this.invoices = invoices;
        this.requestUseCases = requestUseCases;
        this.requests = requests;
        this.invoiceRepository = invoiceRepository;
    }

    @Override
    public int order() {
        return 40;
    }

    @Override
    public String name() {
        return "sales.invoicing";
    }

    @Override
    public SeedResult run(DemoSeedContext context) {
        if (context.tenantId() == null) {
            throw new IllegalStateException("Seed bán hàng cần lms.demo-seed.tenant-id");
        }
        UUID tenantId = context.tenantId();
        DemoPlan plan = context.plan();
        UUID actor = plan.staff().get(2).id();
        int created = 0;
        int skipped = 0;

        if (settingsPort.find(tenantId).filter(s -> s.isComplete()).isPresent()) {
            skipped++;
        } else {
            long version = settingsPort.find(tenantId).map(s -> s.version()).orElse(0L);
            settings.update(new InvoiceSettingsUseCases.UpdateCommand(tenantId, actor, SELLER_NAME, SELLER_TAX_CODE,
                    "Số 1 đường Demo, Hoàn Kiếm, Hà Nội", "02412345678", "ketoan@demo.local", "1", SERIES, false,
                    version));
            created++;
        }

        for (int k = 0; k < candidates(plan).size(); k++) {
            OrderPlan o = candidates(plan).get(k);
            boolean wantsRequest = k % 4 == 1 || (k >= OPEN_FROM && k < OPEN_TO);
            if (wantsRequest) {
                if (requests.findByOrder(tenantId, o.id()).isPresent()) {
                    skipped++;
                } else {
                    requestUseCases.saveByStaff(tenantId, actor, o.id(), companyBuyer(o));
                    created++;
                }
            }
            if (k >= ISSUE_BELOW) {
                continue;
            }
            UUID requestId = DemoIds.of("invoice", o.index());
            boolean existed = invoiceRepository.findByClientRequestId(tenantId, requestId).isPresent();
            InvoiceView view = invoices.issue(new InvoiceUseCases.IssueCommand(tenantId, o.id(), actor, requestId));
            if (existed) {
                skipped++;
            } else {
                created++;
            }
            if (!"ISSUED".equals(view.status())) {
                continue;
            }
            if (k == CANCEL_AT) {
                invoices.cancel(new InvoiceUseCases.CancelCommand(tenantId, view.id(), actor,
                        "Nhập sai thông tin người mua (dữ liệu demo)"));
                created++;
            } else if (k == REPLACE_AT) {
                invoices.replace(new InvoiceUseCases.ReplaceCommand(tenantId, view.id(), actor,
                        "Khách đề nghị đổi sang xuất cho công ty (dữ liệu demo)", companyBuyer(o),
                        DemoIds.of("invoice-replacement", o.index())));
                created++;
            }
        }
        return SeedResult.of(created, skipped);
    }

    /** Đơn đã hoàn tất, đã thu đủ, chưa có phiếu trả; theo thứ tự chỉ số, tối đa {@link #MAX_ORDERS}. */
    static List<OrderPlan> candidates(DemoPlan plan) {
        Set<Integer> returned = new HashSet<>();
        plan.salesReturns().forEach(r -> returned.add(r.orderIndex()));
        return plan.orders().stream()
                .filter(o -> o.status() == DemoPlan.OrderStatus.COMPLETED
                        && o.paymentStatus() == DemoPlan.PaymentStatus.PAID && !returned.contains(o.index()))
                .limit(MAX_ORDERS).toList();
    }

    private static BuyerInput companyBuyer(OrderPlan o) {
        return new BuyerInput("Nguyễn Văn Kế Toán", "Công ty CP Sách Số " + (o.index() + 1),
                String.format("03%08d", 10_000_000 + o.index()), "Số " + (10 + o.index()) + " phố Demo, Hà Nội",
                "ketoan" + o.index() + "@congty.demo.local");
    }
}
