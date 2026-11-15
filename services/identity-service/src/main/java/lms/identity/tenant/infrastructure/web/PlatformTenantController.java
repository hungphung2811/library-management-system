package lms.identity.tenant.infrastructure.web;

import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import lms.identity.tenant.application.AddTenantAdminUseCase;
import lms.identity.tenant.application.AttachUserRequest;
import lms.identity.tenant.application.AttachUserToTenantUseCase;
import lms.identity.tenant.application.CreateTenantRequest;
import lms.identity.tenant.application.CreateTenantUseCase;
import lms.identity.tenant.application.GetTenantUseCase;
import lms.identity.tenant.application.PageResponse;
import lms.identity.tenant.application.RemoveTenantAdminUseCase;
import lms.identity.tenant.application.SearchTenantsUseCase;
import lms.identity.tenant.application.SetTenantStatusUseCase;
import lms.identity.tenant.application.TenantAdminRequest;
import lms.identity.tenant.application.TenantDetailResponse;
import lms.identity.tenant.application.TenantResponse;
import lms.identity.tenant.application.TenantSearchRequest;
import lms.identity.tenant.application.UpdateTenantRequest;
import lms.identity.tenant.application.UpdateTenantUseCase;
import lms.security.CurrentUser;
import lms.security.permission.PlatformAdminOnly;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Quản lý tenant cho người vận hành (super admin, claim {@code platformAdmin=true}); không dùng khóa Controller/method
 * và không cần tenantId (spec §7). nginx/lms-api định tuyến {@code /api/platform/**} tới identity.
 */
@RestController
@RequestMapping("/api/platform/tenants")
@PlatformAdminOnly
public class PlatformTenantController {

    private final CreateTenantUseCase create;
    private final UpdateTenantUseCase update;
    private final SetTenantStatusUseCase status;
    private final SearchTenantsUseCase search;
    private final GetTenantUseCase get;
    private final AddTenantAdminUseCase addAdmin;
    private final RemoveTenantAdminUseCase removeAdmin;
    private final AttachUserToTenantUseCase attach;

    public PlatformTenantController(CreateTenantUseCase create, UpdateTenantUseCase update,
                                    SetTenantStatusUseCase status, SearchTenantsUseCase search, GetTenantUseCase get,
                                    AddTenantAdminUseCase addAdmin, RemoveTenantAdminUseCase removeAdmin,
                                    AttachUserToTenantUseCase attach) {
        this.create = create;
        this.update = update;
        this.status = status;
        this.search = search;
        this.get = get;
        this.addAdmin = addAdmin;
        this.removeAdmin = removeAdmin;
        this.attach = attach;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TenantResponse create(@Valid @RequestBody CreateTenantRequest request, @AuthenticationPrincipal Jwt jwt) {
        return create.execute(request, actor(jwt));
    }

    @GetMapping
    public PageResponse<TenantResponse> search(TenantSearchRequest request) {
        return search.execute(request);
    }

    @GetMapping("/{id}")
    public TenantDetailResponse get(@PathVariable UUID id) {
        return get.execute(id);
    }

    @PutMapping("/{id}")
    public TenantResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateTenantRequest request,
                                 @AuthenticationPrincipal Jwt jwt) {
        return update.execute(id, request, actor(jwt));
    }

    @PostMapping("/{id}/suspend")
    public TenantResponse suspend(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return status.suspend(id, actor(jwt));
    }

    @PostMapping("/{id}/activate")
    public TenantResponse activate(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return status.activate(id, actor(jwt));
    }

    @PostMapping("/{id}/admins")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, UUID> addAdmin(@PathVariable UUID id, @Valid @RequestBody TenantAdminRequest request,
                                      @AuthenticationPrincipal Jwt jwt) {
        return Map.of("userId", addAdmin.execute(id, request, actor(jwt)));
    }

    @DeleteMapping("/{id}/admins/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeAdmin(@PathVariable UUID id, @PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt) {
        removeAdmin.execute(id, userId, actor(jwt));
    }

    @PostMapping("/{id}/members")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void attachMember(@PathVariable UUID id, @Valid @RequestBody AttachUserRequest request,
                             @AuthenticationPrincipal Jwt jwt) {
        attach.execute(id, request, actor(jwt));
    }

    private static UUID actor(Jwt jwt) {
        return CurrentUser.from(jwt).userId();
    }
}
