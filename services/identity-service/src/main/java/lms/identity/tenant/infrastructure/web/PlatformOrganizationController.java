package lms.identity.tenant.infrastructure.web;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lms.identity.tenant.application.ManageOrganizationUseCase;
import lms.identity.tenant.application.OrganizationRequest;
import lms.identity.tenant.application.OrganizationResponse;
import lms.security.CurrentUser;
import lms.security.permission.PlatformAdminOnly;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Quản lý trường (organization) cho người vận hành; không có xóa (tenant tham chiếu tới trường). */
@RestController
@RequestMapping("/api/platform/organizations")
@PlatformAdminOnly
public class PlatformOrganizationController {

    private final ManageOrganizationUseCase organizations;

    public PlatformOrganizationController(ManageOrganizationUseCase organizations) {
        this.organizations = organizations;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrganizationResponse create(@Valid @RequestBody OrganizationRequest request,
                                       @AuthenticationPrincipal Jwt jwt) {
        return organizations.create(request, CurrentUser.from(jwt).userId());
    }

    @GetMapping
    public List<OrganizationResponse> list() {
        return organizations.list();
    }

    @GetMapping("/{id}")
    public OrganizationResponse get(@PathVariable UUID id) {
        return organizations.get(id);
    }

    @PutMapping("/{id}")
    public OrganizationResponse rename(@PathVariable UUID id, @Valid @RequestBody OrganizationRequest request,
                                       @AuthenticationPrincipal Jwt jwt) {
        return organizations.rename(id, request, CurrentUser.from(jwt).userId());
    }
}
