package lms.identity.apiclient.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lms.identity.apiclient.application.ApplicationViews.ApplicationDetail;
import lms.identity.apiclient.application.ApplicationViews.ApplicationSummary;
import lms.identity.apiclient.application.ApplicationViews.CreatedCredential;
import lms.identity.apiclient.application.ApplicationViews.CredentialView;
import lms.identity.apiclient.application.ApplicationViews.DomainList;
import lms.identity.apiclient.application.ApplicationViews.HistoryItem;
import lms.identity.apiclient.application.CreateApplicationUseCase;
import lms.identity.apiclient.application.ManageApplicationDomainsUseCase;
import lms.identity.apiclient.application.ManageApplicationUseCase;
import lms.identity.apiclient.application.ManageCredentialsUseCase;
import lms.identity.apiclient.application.QueryApplicationUsageUseCase;
import lms.identity.apiclient.application.QueryApplicationUsageUseCase.UsageReport;
import lms.identity.apiclient.application.QueryApplicationUseCase;
import lms.identity.tenant.application.StorefrontOriginResponse;
import lms.security.CurrentUser;
import lms.tenant.TenantContext;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant admin tự quản lý Application, scope, domain allowlist và credential của tenant mình (SaaS API Authentication
 * Q3, ADR-0027). KHÔNG có annotation quyền: khóa động {@code ApplicationController/<method>} do PermissionInterceptor tự
 * đăng ký và kiểm (role is_admin đi qua sẵn; tenant có thể gán khóa cho role khác). Đổi tên lớp/method làm đổi khóa
 * (role đã gán mất quyền): tên method đã chốt, không đổi. Tenant lấy từ token (TenantContext), KHÔNG nhận tenantId từ
 * body/query/đường dẫn; id của tenant khác là 404 cùng thân với "không tồn tại". lms-api định tuyến
 * {@code /api/applications/**}.
 */
@RestController
@RequestMapping("/internal/applications")
public class ApplicationController {

    public record CreateApplicationRequest(@NotBlank @Size(max = 100) String name, @NotBlank String kind,
                                           @Min(1) @Max(1_000_000) Integer rateLimitPerMinute) {
    }

    public record UpdateApplicationRequest(@NotBlank @Size(max = 100) String name,
                                           @Min(1) @Max(1_000_000) Integer rateLimitPerMinute) {
    }

    public record ReplaceScopesRequest(@NotNull List<@NotBlank String> scopes) {
    }

    /** Cả thân lẫn từng trường đều tùy chọn: thiếu thì dùng hạn mặc định cấu hình. */
    public record CreateCredentialRequest(@Min(1) Integer expiresInDays) {
    }

    public record RevokeCredentialRequest(String reason) {
    }

    public record AddDomainRequest(@NotBlank String origin) {
    }

    /** Khoảng ngày (UTC, gồm hai đầu, ISO yyyy-MM-dd) của thống kê sử dụng; thiếu thì 30 ngày gần nhất, tối đa 31 ngày. */
    public record UsageRange(@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    }

    private final CreateApplicationUseCase create;
    private final QueryApplicationUseCase query;
    private final ManageApplicationUseCase manage;
    private final ManageCredentialsUseCase credentials;
    private final ManageApplicationDomainsUseCase domains;
    private final QueryApplicationUsageUseCase usage;

    public ApplicationController(CreateApplicationUseCase create, QueryApplicationUseCase query,
                                 ManageApplicationUseCase manage, ManageCredentialsUseCase credentials,
                                 ManageApplicationDomainsUseCase domains, QueryApplicationUsageUseCase usage) {
        this.usage = usage;
        this.create = create;
        this.query = query;
        this.manage = manage;
        this.credentials = credentials;
        this.domains = domains;
    }

    @GetMapping
    public List<ApplicationSummary> list() {
        return query.list(tenant());
    }

    @GetMapping("/{id}")
    public ApplicationDetail get(@PathVariable UUID id) {
        return query.get(tenant(), id);
    }

    @PostMapping
    public ResponseEntity<ApplicationDetail> create(@Valid @RequestBody CreateApplicationRequest request,
                                                    @AuthenticationPrincipal Jwt jwt) {
        ApplicationDetail created = create.execute(tenant(), new CreateApplicationUseCase.Command(request.name(),
                request.kind(), request.rateLimitPerMinute()), actor(jwt));
        return ResponseEntity.created(URI.create("/api/applications/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public ApplicationDetail update(@PathVariable UUID id, @Valid @RequestBody UpdateApplicationRequest request,
                                    @AuthenticationPrincipal Jwt jwt) {
        return manage.update(tenant(), id, request.name(), request.rateLimitPerMinute(), actor(jwt));
    }

    /** Thu hồi (xóa mềm): credential bị thu hồi, domain bị xóa. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        manage.delete(tenant(), id, actor(jwt));
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/scopes")
    public ApplicationDetail replaceScopes(@PathVariable UUID id, @Valid @RequestBody ReplaceScopesRequest request,
                                           @AuthenticationPrincipal Jwt jwt) {
        return manage.replaceScopes(tenant(), id, request.scopes(), actor(jwt));
    }

    /**
     * Tạo credential mới (cũng là bước đầu của xoay vòng: tối đa 2 credential dùng được cùng lúc). Phản hồi chứa
     * {@code clientSecret} đúng MỘT lần, kèm {@code Cache-Control: no-store}.
     */
    @PostMapping("/{id}/credentials")
    public ResponseEntity<CreatedCredential> createCredential(
            @PathVariable UUID id, @Valid @RequestBody(required = false) CreateCredentialRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Integer days = request == null ? null : request.expiresInDays();
        CreatedCredential created = credentials.create(tenant(), id, days, actor(jwt));
        return ResponseEntity.created(URI.create("/api/applications/" + id + "/credentials/" + created.credentialId()))
                .cacheControl(CacheControl.noStore()).body(created);
    }

    @PostMapping("/{id}/credentials/{credentialId}/revocation")
    public CredentialView revokeCredential(@PathVariable UUID id, @PathVariable UUID credentialId,
                                           @RequestBody(required = false) RevokeCredentialRequest request,
                                           @AuthenticationPrincipal Jwt jwt) {
        return credentials.revoke(tenant(), id, credentialId, request == null ? null : request.reason(), actor(jwt));
    }

    @PostMapping("/{id}/site-key/rotation")
    public ApplicationDetail rotateSiteKey(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return manage.rotateSiteKey(tenant(), id, actor(jwt));
    }

    @GetMapping("/{id}/domains")
    public DomainList listDomains(@PathVariable UUID id) {
        return domains.list(tenant(), id);
    }

    @PostMapping("/{id}/domains")
    public ResponseEntity<StorefrontOriginResponse> addDomain(@PathVariable UUID id,
                                                              @Valid @RequestBody AddDomainRequest request,
                                                              @AuthenticationPrincipal Jwt jwt) {
        StorefrontOriginResponse added = domains.add(tenant(), id, request.origin(), actor(jwt));
        return ResponseEntity.created(URI.create("/api/applications/" + id + "/domains/" + added.id())).body(added);
    }

    @DeleteMapping("/{id}/domains/{domainId}")
    public ResponseEntity<Void> removeDomain(@PathVariable UUID id, @PathVariable UUID domainId,
                                             @AuthenticationPrincipal Jwt jwt) {
        domains.remove(tenant(), id, domainId, actor(jwt));
        return ResponseEntity.noContent().build();
    }

    /** Thống kê sử dụng theo ngày (khóa động ApplicationController/usage). Application của tenant khác là 404. */
    @GetMapping("/{id}/usage")
    public UsageReport usage(@PathVariable UUID id, UsageRange range) {
        return usage.usage(tenant(), id, range.from(), range.to());
    }

    @GetMapping("/{id}/history")
    public List<HistoryItem> history(@PathVariable UUID id) {
        return query.history(tenant(), id);
    }

    private static UUID tenant() {
        return TenantContext.require();
    }

    private static UUID actor(Jwt jwt) {
        return CurrentUser.from(jwt).userId();
    }
}
