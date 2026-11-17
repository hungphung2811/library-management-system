package lms.identity.tenant.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.UUID;
import lms.identity.tenant.application.AddStorefrontOriginUseCase;
import lms.identity.tenant.application.ListStorefrontOriginsUseCase;
import lms.identity.tenant.application.RemoveStorefrontOriginUseCase;
import lms.identity.tenant.application.SetStorefrontOriginEnabledUseCase;
import lms.identity.tenant.application.StorefrontOriginListResponse;
import lms.identity.tenant.application.StorefrontOriginResponse;
import lms.security.CurrentUser;
import lms.security.permission.PlatformAdminOnly;
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
 * Platform admin quản lý origin storefront của tenant (ADR-0013 2.8). {@code @PlatformAdminOnly}: không dùng khóa
 * Controller/method. lms-api chuyển nguyên {@code /api/platform/**}. Tên method (list, add, update, remove) giữ ổn định.
 */
@RestController
@RequestMapping("/api/platform/tenants/{tenantId}/storefront-origins")
@PlatformAdminOnly
public class PlatformStorefrontOriginController {

    public record AddOriginRequest(@NotBlank String origin) {
    }

    public record UpdateOriginRequest(@NotNull Boolean enabled) {
    }

    private final ListStorefrontOriginsUseCase list;
    private final AddStorefrontOriginUseCase add;
    private final SetStorefrontOriginEnabledUseCase setEnabled;
    private final RemoveStorefrontOriginUseCase remove;

    public PlatformStorefrontOriginController(ListStorefrontOriginsUseCase list, AddStorefrontOriginUseCase add,
                                              SetStorefrontOriginEnabledUseCase setEnabled,
                                              RemoveStorefrontOriginUseCase remove) {
        this.list = list;
        this.add = add;
        this.setEnabled = setEnabled;
        this.remove = remove;
    }

    @GetMapping
    public StorefrontOriginListResponse list(@PathVariable UUID tenantId) {
        return list.execute(tenantId);
    }

    @PostMapping
    public ResponseEntity<StorefrontOriginResponse> add(@PathVariable UUID tenantId,
                                                        @Valid @RequestBody AddOriginRequest request,
                                                        @AuthenticationPrincipal Jwt jwt) {
        StorefrontOriginResponse created = add.execute(tenantId, request.origin(), actor(jwt));
        return ResponseEntity.created(URI.create("/api/platform/tenants/" + tenantId + "/storefront-origins/"
                + created.id())).body(created);
    }

    @PutMapping("/{originId}")
    public StorefrontOriginResponse update(@PathVariable UUID tenantId, @PathVariable UUID originId,
                                           @Valid @RequestBody UpdateOriginRequest request,
                                           @AuthenticationPrincipal Jwt jwt) {
        return setEnabled.execute(tenantId, originId, request.enabled(), actor(jwt));
    }

    @DeleteMapping("/{originId}")
    public ResponseEntity<Void> remove(@PathVariable UUID tenantId, @PathVariable UUID originId,
                                       @AuthenticationPrincipal Jwt jwt) {
        remove.execute(tenantId, originId, actor(jwt));
        return ResponseEntity.noContent().build();
    }

    private static UUID actor(Jwt jwt) {
        return CurrentUser.from(jwt).userId();
    }
}
