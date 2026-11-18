package lms.identity.apiclient.infrastructure.web;

import java.util.List;
import java.util.UUID;
import lms.identity.apiclient.application.ApplicationViews.ApplicationDetail;
import lms.identity.apiclient.application.ApplicationViews.ApplicationSummary;
import lms.identity.apiclient.application.ApplicationViews.HistoryItem;
import lms.identity.apiclient.application.ManageApplicationUseCase;
import lms.identity.apiclient.application.QueryApplicationUseCase;
import lms.security.CurrentUser;
import lms.security.permission.PlatformAdminOnly;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform admin xem, tạm ngưng, kích hoạt lại và thu hồi Application của MỘT tenant (SaaS API Authentication Q3).
 * {@code @PlatformAdminOnly}: không dùng khóa Controller/method; tenant lấy từ đường dẫn (như origin storefront, ADR-0013),
 * mọi truy vấn vẫn kèm (tenantId, id). lms-api chuyển nguyên {@code /api/platform/**}. Không tạo Application và không
 * cấp/đổi credential hộ tenant (secret chỉ nên đến tay tenant); tên method giữ ổn định.
 */
@RestController
@RequestMapping("/api/platform/tenants/{tenantId}/applications")
@PlatformAdminOnly
public class PlatformApplicationController {

    private final QueryApplicationUseCase query;
    private final ManageApplicationUseCase manage;

    public PlatformApplicationController(QueryApplicationUseCase query, ManageApplicationUseCase manage) {
        this.query = query;
        this.manage = manage;
    }

    @GetMapping
    public List<ApplicationSummary> list(@PathVariable UUID tenantId) {
        return query.list(tenantId);
    }

    @GetMapping("/{id}")
    public ApplicationDetail get(@PathVariable UUID tenantId, @PathVariable UUID id) {
        return query.get(tenantId, id);
    }

    @GetMapping("/{id}/history")
    public List<HistoryItem> history(@PathVariable UUID tenantId, @PathVariable UUID id) {
        return query.history(tenantId, id);
    }

    @PostMapping("/{id}/suspension")
    public ApplicationDetail suspend(@PathVariable UUID tenantId, @PathVariable UUID id,
                                     @AuthenticationPrincipal Jwt jwt) {
        return manage.suspend(tenantId, id, actor(jwt));
    }

    @PostMapping("/{id}/reactivation")
    public ApplicationDetail reactivate(@PathVariable UUID tenantId, @PathVariable UUID id,
                                        @AuthenticationPrincipal Jwt jwt) {
        return manage.reactivate(tenantId, id, actor(jwt));
    }

    /** Thu hồi (xóa mềm): credential bị thu hồi, domain bị xóa. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID tenantId, @PathVariable UUID id,
                                       @AuthenticationPrincipal Jwt jwt) {
        manage.delete(tenantId, id, actor(jwt));
        return ResponseEntity.noContent().build();
    }

    private static UUID actor(Jwt jwt) {
        return CurrentUser.from(jwt).userId();
    }
}
