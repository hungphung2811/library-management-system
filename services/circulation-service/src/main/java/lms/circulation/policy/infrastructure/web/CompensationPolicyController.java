package lms.circulation.policy.infrastructure.web;

import jakarta.validation.Valid;
import lms.circulation.policy.application.CompensationPolicyResponse;
import lms.circulation.policy.application.UpdateCompensationPolicyRequest;
import lms.circulation.policy.application.UpdateCompensationPolicyUseCase;
import lms.circulation.policy.infrastructure.cache.CachedCompensationReader;
import lms.security.CurrentUser;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantModule;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Khóa quyền sinh tự động: CompensationPolicyController/get và CompensationPolicyController/update. */
@RestController
@RequiresModule(TenantModule.LENDING)
@RequestMapping("/internal/circulation/compensation-policy")
public class CompensationPolicyController {

    private final CachedCompensationReader reader;
    private final UpdateCompensationPolicyUseCase update;

    public CompensationPolicyController(CachedCompensationReader reader, UpdateCompensationPolicyUseCase update) {
        this.reader = reader;
        this.update = update;
    }

    @GetMapping
    public CompensationPolicyResponse get() {
        return reader.policy();
    }

    @PutMapping
    public CompensationPolicyResponse update(@Valid @RequestBody UpdateCompensationPolicyRequest request,
                                             @AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cần đăng nhập");
        }
        return update.execute(request, CurrentUser.from(jwt).userId());
    }
}
