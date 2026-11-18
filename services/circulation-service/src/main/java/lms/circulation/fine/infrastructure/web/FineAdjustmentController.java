package lms.circulation.fine.infrastructure.web;

import jakarta.validation.Valid;
import java.util.UUID;
import lms.circulation.fine.application.FineAdjustmentRequests;
import lms.circulation.fine.application.FineAdjustmentResponse;
import lms.circulation.fine.application.FineAdjustmentUseCases;
import lms.circulation.fine.application.FinePageResponse;
import lms.security.CurrentUser;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantModule;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Miễn/giảm khoản phạt chưa thu. Khóa quyền sinh tự động: FineAdjustmentController/request, list, approve, reject
 * (approve và reject là khóa riêng tách khỏi request). Người duyệt phải khác người đề nghị (409 APPROVER_MUST_DIFFER),
 * kiểm trong use case, không dựa vào role. Platform admin dùng X-Tenant-Id như ADR-0008/0016.
 */
@RestController
@RequiresModule(TenantModule.LENDING)
@RequestMapping("/internal/fines")
public class FineAdjustmentController {

    private final FineAdjustmentUseCases useCases;

    public FineAdjustmentController(FineAdjustmentUseCases useCases) {
        this.useCases = useCases;
    }

    @PostMapping("/{id}/adjustment-requests")
    public ResponseEntity<FineAdjustmentResponse> request(@PathVariable UUID id,
                                                          @Valid @RequestBody FineAdjustmentRequests.Create request,
                                                          @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED).body(useCases.request(id, request, userId(jwt)));
    }

    @GetMapping("/adjustment-requests")
    public FinePageResponse<FineAdjustmentResponse> list(FineAdjustmentRequests.Search query) {
        return useCases.list(query);
    }

    @PostMapping("/adjustment-requests/{requestId}/approve")
    public FineAdjustmentResponse approve(@PathVariable UUID requestId,
                                          @Valid @RequestBody(required = false) FineAdjustmentRequests.Decision body,
                                          @AuthenticationPrincipal Jwt jwt) {
        return useCases.approve(requestId, body, userId(jwt));
    }

    @PostMapping("/adjustment-requests/{requestId}/reject")
    public FineAdjustmentResponse reject(@PathVariable UUID requestId,
                                         @Valid @RequestBody(required = false) FineAdjustmentRequests.Decision body,
                                         @AuthenticationPrincipal Jwt jwt) {
        return useCases.reject(requestId, body, userId(jwt));
    }

    static UUID userId(Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cần đăng nhập");
        }
        return CurrentUser.from(jwt).userId();
    }
}
