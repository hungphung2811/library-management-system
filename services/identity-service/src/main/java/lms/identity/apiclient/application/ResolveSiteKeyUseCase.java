package lms.identity.apiclient.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.UUID;
import lms.identity.apiclient.domain.Application;
import lms.identity.apiclient.domain.ApplicationKind;
import lms.identity.apiclient.domain.ApplicationRepository;
import lms.identity.apiclient.domain.ApplicationStatus;
import lms.identity.apiclient.domain.SiteKey;
import lms.identity.tenant.application.ResolveStorefrontTenantUseCase;
import lms.identity.tenant.application.StorefrontTenant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phân giải site key công khai thành tenant + Application cho cấp token storefront (SaaS API Authentication, pha P2, D4).
 * Site key chỉ CHỌN Application, không phải bằng chứng xác thực. Trả rỗng cho MỌI lý do thất bại (sai định dạng, không
 * tồn tại, đã xoay vòng/thu hồi, Application không ACTIVE hoặc không phải PUBLIC_SITE, tenant ngưng hoặc không bật
 * SALES/LENDING): người gọi không phân biệt được. Mọi nhánh cùng chi phí: đúng một truy vấn Application rồi luôn chạy
 * trọn truy vấn tenant/module/origin (id giả khi không có). Site key khớp ở DB (UNIQUE) rồi vẫn so lại hằng thời gian
 * bằng {@link MessageDigest#isEqual}. KHÔNG log site key.
 */
public class ResolveSiteKeyUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(ResolveSiteKeyUseCase.class);
    /** Khóa không bao giờ khớp ràng buộc định dạng nên không thể trùng một site key thật. */
    private static final String NEVER_MATCHES = "-";

    /** allowedOrigins của tenant trong kết quả chỉ gồm origin đang bật gắn đúng Application này. */
    public record ResolvedSite(UUID applicationId, StorefrontTenant tenant) {
    }

    private enum Failure { BAD_FORMAT, UNKNOWN_KEY, NOT_PUBLIC_SITE, APPLICATION_NOT_ACTIVE, TENANT_NOT_AVAILABLE }

    private final ApplicationRepository applications;
    private final ResolveStorefrontTenantUseCase tenants;

    public ResolveSiteKeyUseCase(ApplicationRepository applications, ResolveStorefrontTenantUseCase tenants) {
        this.applications = applications;
        this.tenants = tenants;
    }

    public Optional<ResolvedSite> resolve(String siteKey) {
        boolean wellFormed = SiteKey.isValid(siteKey);
        Optional<Application> found = applications.findVisibleBySiteKey(wellFormed ? siteKey : NEVER_MATCHES);
        Optional<StorefrontTenant> tenant = tenants.executeForApplication(
                found.map(Application::tenantId).orElse(null), found.map(Application::id).orElse(null));

        Failure failure = null;
        if (!wellFormed) {
            failure = Failure.BAD_FORMAT;
        } else if (found.isEmpty() || !sameKey(siteKey, found.get().siteKey())) {
            failure = Failure.UNKNOWN_KEY;
        } else if (found.get().kind() != ApplicationKind.PUBLIC_SITE) {
            failure = Failure.NOT_PUBLIC_SITE;
        } else if (found.get().status() != ApplicationStatus.ACTIVE) {
            failure = Failure.APPLICATION_NOT_ACTIVE;
        } else if (tenant.isEmpty()) {
            failure = Failure.TENANT_NOT_AVAILABLE;
        }
        if (failure != null) {
            // Chỉ lý do + applicationId (nếu có); không bao giờ log site key
            LOG.debug("site-key denied reason={} applicationId={}", failure, found.map(Application::id).orElse(null));
            return Optional.empty();
        }
        return Optional.of(new ResolvedSite(found.get().id(), tenant.get()));
    }

    private static boolean sameKey(String presented, String stored) {
        if (stored == null) {
            return false;
        }
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), stored.getBytes(StandardCharsets.UTF_8));
    }
}
