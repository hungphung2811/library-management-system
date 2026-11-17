package lms.identity.auth.infrastructure.web;

import lms.identity.auth.application.ListMySessionsUseCase;
import lms.identity.auth.application.RevokeMySessionUseCase;
import lms.identity.auth.application.RevokeMySessionsUseCase;
import lms.identity.auth.application.RevokeSessionsRequest;
import lms.identity.auth.application.RevokedMySessionsResponse;
import lms.identity.auth.application.SessionView;
import lms.security.CurrentUser;
import lms.security.JwtClaims;
import lms.security.permission.AuthenticatedOnly;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phiên đăng nhập (thiết bị) CỦA TÔI (ADR-0017). Chủ thể luôn là {@code sub} của token, không nhận userId nào; không
 * theo tenant (mọi tenant, mọi kênh của tài khoản). Mọi method {@code @AuthenticatedOnly} nên không có khóa quyền nào
 * được đăng ký; token dịch vụ ({@code svc}) bị PermissionInterceptor từ chối 403. Phản hồi có IP nên {@code no-store}.
 * Thu hồi từ xa chỉ chặn refresh: access token của thiết bị kia còn dùng được tối đa đến hết TTL (mặc định 15 phút).
 */
@RestController
@RequestMapping("/internal/auth/sessions")
public class SessionController {

    private final ListMySessionsUseCase list;
    private final RevokeMySessionUseCase revokeOne;
    private final RevokeMySessionsUseCase revokeMany;

    public SessionController(ListMySessionsUseCase list, RevokeMySessionUseCase revokeOne,
                             RevokeMySessionsUseCase revokeMany) {
        this.list = list;
        this.revokeOne = revokeOne;
        this.revokeMany = revokeMany;
    }

    @AuthenticatedOnly
    @GetMapping
    public ResponseEntity<List<SessionView>> list(@AuthenticationPrincipal Jwt jwt) {
        List<SessionView> sessions = list.execute(CurrentUser.from(jwt).userId(), sessionId(jwt));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(sessions);
    }

    @AuthenticatedOnly
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId) {
        revokeOne.execute(CurrentUser.from(jwt).userId(), sessionId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @AuthenticatedOnly
    @PostMapping("/revocation")
    public ResponseEntity<RevokedMySessionsResponse> revokeMany(@AuthenticationPrincipal Jwt jwt,
                                                                @Valid @RequestBody RevokeSessionsRequest request) {
        int revoked = revokeMany.execute(CurrentUser.from(jwt).userId(), sessionId(jwt), request.keepCurrent());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new RevokedMySessionsResponse(revoked));
    }

    /** Claim sid của access token; thiếu hoặc sai định dạng (token phát trước ADR-0017) thì null. */
    private static UUID sessionId(Jwt jwt) {
        String value = jwt == null ? null : jwt.getClaimAsString(JwtClaims.SESSION_ID);
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
