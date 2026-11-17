package lms.circulation.policy.infrastructure.web;

import jakarta.validation.Valid;
import lms.circulation.policy.application.CirculationPolicyResponse;
import lms.circulation.policy.application.GetCirculationPolicyUseCase;
import lms.circulation.policy.application.UpdateCirculationPolicyRequest;
import lms.circulation.policy.application.UpdateCirculationPolicyUseCase;
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

/** Khóa quyền sinh tự động: CirculationPolicyController/get và CirculationPolicyController/update. */
@RestController
@RequiresModule(TenantModule.LENDING)
@RequestMapping("/internal/circulation/policy")
public class CirculationPolicyController {

    private final GetCirculationPolicyUseCase getPolicy;
    private final UpdateCirculationPolicyUseCase updatePolicy;

    public CirculationPolicyController(GetCirculationPolicyUseCase getPolicy, UpdateCirculationPolicyUseCase updatePolicy) {
        this.getPolicy = getPolicy;
        this.updatePolicy = updatePolicy;
    }

    @GetMapping
    public CirculationPolicyResponse get() {
        return getPolicy.execute();
    }

    @PutMapping
    public CirculationPolicyResponse update(@Valid @RequestBody UpdateCirculationPolicyRequest request,
                                            @AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cần đăng nhập");
        }
        return updatePolicy.execute(request, CurrentUser.from(jwt).userId());
    }
}
