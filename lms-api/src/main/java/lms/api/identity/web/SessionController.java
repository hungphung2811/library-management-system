package lms.api.identity.web;

import lms.api.identity.client.AuthClient;
import lms.api.identity.dto.AuthDtos.RevokeSessionsRequest;
import lms.api.identity.dto.AuthDtos.SessionView;
import lms.api.identity.dto.UserDtos.RevokedSessionsView;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thiết bị đăng nhập của chính người dùng (ADR-0017). Cần đăng nhập; chủ thể là sub của token nên không nhận userId.
 * Phản hồi có IP nên luôn no-store. Thu hồi từ xa chỉ chặn làm mới token; access token đã phát còn dùng tối đa 15 phút.
 */
@RestController
@RequestMapping("/api/auth/sessions")
public class SessionController {

    private final AuthClient auth;

    public SessionController(AuthClient auth) {
        this.auth = auth;
    }

    @GetMapping
    public ResponseEntity<List<SessionView>> list() {
        return noStore(auth.mySessions());
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> revoke(@PathVariable UUID sessionId) {
        auth.revokeMySession(sessionId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/revocation")
    public ResponseEntity<RevokedSessionsView> revokeAll(@Valid @RequestBody RevokeSessionsRequest request) {
        return noStore(auth.revokeMySessions(request.keepCurrent()));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
