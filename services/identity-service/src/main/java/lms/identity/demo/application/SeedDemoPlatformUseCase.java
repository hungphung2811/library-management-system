package lms.identity.demo.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lms.demo.plan.DemoIds;
import lms.demo.plan.DemoPlan;
import lms.identity.apiclient.application.ApplicationViews.ApplicationSummary;
import lms.identity.apiclient.application.CreateApplicationUseCase;
import lms.identity.apiclient.application.ManageApplicationDomainsUseCase;
import lms.identity.apiclient.application.ManageApplicationUseCase;
import lms.identity.apiclient.application.ManageCredentialsUseCase;
import lms.identity.apiclient.application.QueryApplicationUseCase;
import lms.identity.contract.application.ContractPaymentUseCase;
import lms.identity.contract.application.ContractQueryUseCase;
import lms.identity.contract.application.ContractRequests.PaymentRequest;
import lms.identity.tenant.application.AddStorefrontOriginUseCase;
import lms.identity.tenant.application.ListStorefrontOriginsUseCase;
import lms.identity.tenant.application.SetStorefrontOriginEnabledUseCase;
import lms.tenant.TenantContext;

/**
 * Seed phần nền tảng của tenant demo trong identity (ADR-0018): thanh toán của hợp đồng thuê, storefront origins (một
 * đang bật, một đã tắt), Application của Developer Portal (một PUBLIC_SITE có site key và domain, một INTEGRATION có
 * scope và credential) kèm lịch sử. Chỉ dùng use case sẵn có của các context. Chạy SAU {@link SeedDemoIdentityUseCase}
 * (cần tenant và hợp đồng đã có).
 * <p>
 * Idempotent theo khóa tự nhiên: thanh toán theo mã tham chiếu {@code DEMO-PAY-n} trong hợp đồng, origin theo giá trị
 * origin, Application theo tên. Secret của credential do use case tự sinh ngẫu nhiên và bị bỏ đi (không log, không
 * lưu nơi nào): dữ liệu demo chỉ có băm và gợi ý secret, không dùng được để lấy token.
 */
public class SeedDemoPlatformUseCase {

    static final String ORIGIN_ACTIVE = "https://shop.demo.local";
    static final String ORIGIN_DISABLED = "https://cu.demo.local";
    static final String SITE_APP_NAME = "Website cửa hàng Demo";
    static final String SITE_APP_ORIGIN = "https://sach.demo.local";
    static final String INTEGRATION_APP_NAME = "Kết nối kế toán Demo";
    static final List<String> INTEGRATION_SCOPES = List.of("books:read", "inventory:read", "orders:read");
    static final long PAYMENT_1 = 12_000_000L;
    static final long PAYMENT_2 = 6_000_000L;

    public record Result(int created, int skipped) {
    }

    private final ContractQueryUseCase contractQuery;
    private final ContractPaymentUseCase contractPayments;
    private final ListStorefrontOriginsUseCase listOrigins;
    private final AddStorefrontOriginUseCase addOrigin;
    private final SetStorefrontOriginEnabledUseCase setOriginEnabled;
    private final QueryApplicationUseCase queryApplications;
    private final CreateApplicationUseCase createApplication;
    private final ManageApplicationUseCase manageApplication;
    private final ManageApplicationDomainsUseCase applicationDomains;
    private final ManageCredentialsUseCase credentials;

    public SeedDemoPlatformUseCase(ContractQueryUseCase contractQuery, ContractPaymentUseCase contractPayments,
                                   ListStorefrontOriginsUseCase listOrigins, AddStorefrontOriginUseCase addOrigin,
                                   SetStorefrontOriginEnabledUseCase setOriginEnabled,
                                   QueryApplicationUseCase queryApplications,
                                   CreateApplicationUseCase createApplication,
                                   ManageApplicationUseCase manageApplication,
                                   ManageApplicationDomainsUseCase applicationDomains,
                                   ManageCredentialsUseCase credentials) {
        this.contractQuery = contractQuery;
        this.contractPayments = contractPayments;
        this.listOrigins = listOrigins;
        this.addOrigin = addOrigin;
        this.setOriginEnabled = setOriginEnabled;
        this.queryApplications = queryApplications;
        this.createApplication = createApplication;
        this.manageApplication = manageApplication;
        this.applicationDomains = applicationDomains;
        this.credentials = credentials;
    }

    public Result execute(DemoPlan plan) {
        Counter counter = new Counter();
        UUID tenantId = DemoIds.tenant();
        UUID actor = SeedDemoIdentityUseCase.SEED_ACTOR;
        UUID tenantAdmin = plan.staff().stream().filter(s -> s.role() == DemoPlan.StaffRole.TENANT_ADMIN)
                .findFirst().orElseThrow().id();
        ensurePayments(tenantId, actor, plan.today(), counter);
        ensureStorefrontOrigins(tenantId, actor, counter);
        TenantContext.runAs(tenantId, () -> ensureApplications(tenantId, tenantAdmin, counter));
        return new Result(counter.created, counter.skipped);
    }

    private void ensurePayments(UUID tenantId, UUID actor, LocalDate today, Counter counter) {
        var contract = contractQuery.listByTenant(tenantId).contracts().stream()
                .filter(c -> SeedDemoIdentityUseCase.CONTRACT_NAME.equals(c.name())).findFirst().orElse(null);
        if (contract == null) {
            throw new IllegalStateException("Chưa có hợp đồng demo để ghi thanh toán (chạy bước identity trước)");
        }
        var detail = contractQuery.get(contract.id());
        long[] amounts = {PAYMENT_1, PAYMENT_2};
        int[] daysAgo = {330, 150};
        for (int i = 0; i < amounts.length; i++) {
            String reference = "DEMO-PAY-" + (i + 1);
            if (detail.payments().stream().anyMatch(p -> reference.equals(p.reference()))) {
                counter.skipped++;
                continue;
            }
            contractPayments.record(contract.id(), new PaymentRequest(today.minusDays(daysAgo[i]), amounts[i],
                    reference, "Thanh toán đợt " + (i + 1) + " (dữ liệu demo)"), actor);
            counter.created++;
        }
    }

    private void ensureStorefrontOrigins(UUID tenantId, UUID actor, Counter counter) {
        var existing = listOrigins.execute(tenantId).origins();
        if (existing.stream().noneMatch(o -> ORIGIN_ACTIVE.equals(o.origin()))) {
            addOrigin.execute(tenantId, ORIGIN_ACTIVE, actor);
            counter.created++;
        } else {
            counter.skipped++;
        }
        if (existing.stream().noneMatch(o -> ORIGIN_DISABLED.equals(o.origin()))) {
            var added = addOrigin.execute(tenantId, ORIGIN_DISABLED, actor);
            setOriginEnabled.execute(tenantId, added.id(), false, actor);
            counter.created++;
        } else {
            counter.skipped++;
        }
    }

    private void ensureApplications(UUID tenantId, UUID actor, Counter counter) {
        List<ApplicationSummary> apps = queryApplications.list(tenantId);

        if (apps.stream().noneMatch(a -> SITE_APP_NAME.equals(a.name()))) {
            var created = createApplication.execute(tenantId,
                    new CreateApplicationUseCase.Command(SITE_APP_NAME, "PUBLIC_SITE", 120), actor);
            applicationDomains.add(tenantId, created.id(), SITE_APP_ORIGIN, actor);
            counter.created++;
        } else {
            counter.skipped++;
        }

        if (apps.stream().noneMatch(a -> INTEGRATION_APP_NAME.equals(a.name()))) {
            var created = createApplication.execute(tenantId,
                    new CreateApplicationUseCase.Command(INTEGRATION_APP_NAME, "INTEGRATION", 60), actor);
            manageApplication.replaceScopes(tenantId, created.id(), INTEGRATION_SCOPES, actor);
            // Credential: secret sinh ngẫu nhiên và bỏ đi (chỉ còn băm + gợi ý); một cái đã thu hồi, một còn hiệu lực
            var old = credentials.create(tenantId, created.id(), 30, actor);
            credentials.revoke(tenantId, created.id(), old.credentialId(), "ROTATED", actor);
            credentials.create(tenantId, created.id(), 90, actor);
            counter.created++;
        } else {
            counter.skipped++;
        }
    }

    private static final class Counter {
        int created;
        int skipped;
    }
}
