package lms.identity.user.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import lms.common.kernel.ErrorResponse;
import lms.identity.user.application.LookupNotificationRecipientsUseCase;
import lms.identity.user.domain.InvalidUserException;
import lms.security.permission.PublicEndpoint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tra email và tên hiển thị người nhận thông báo cho support-service (ADR-0030 D5). Công khai ở lớp xác thực, bảo vệ
 * bằng bí mật riêng NOTIFICATION_CLIENT_SECRET trong header (KHÔNG dùng INTERNAL_API_KEY: khóa đó mọi service đều giữ,
 * lộ một service là đọc được email mọi tenant); chưa cấu hình bí mật thì từ chối mọi lời gọi. So sánh hằng thời gian.
 * Chỉ trả dữ liệu tối thiểu theo tenant được hỏi; không đi qua lms-api/nginx.
 */
@PublicEndpoint
@RestController
@RequestMapping("/internal/notification-recipients")
public class NotificationRecipientController {

    public static final String SECRET_HEADER = "X-Notification-Client-Secret";

    /** userIds rỗng hợp lệ: chỉ lấy tên tenant (người nhận trực tiếp không có tài khoản). */
    public record LookupRequest(@NotNull UUID tenantId, @Size(max = 100) List<@NotNull UUID> userIds) {
    }

    public record RecipientBody(UUID userId, String email, String displayName, boolean active) {
        @Override
        public String toString() {
            return "RecipientBody[userId=" + userId + "]";
        }
    }

    public record LookupResponse(String tenantDisplayName, List<RecipientBody> recipients) {
    }

    private final LookupNotificationRecipientsUseCase lookup;
    private final byte[] secret;

    public NotificationRecipientController(LookupNotificationRecipientsUseCase lookup,
                                           @Value("${lms.notification.client-secret:}") String secret) {
        this.lookup = lookup;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/lookup")
    public ResponseEntity<?> lookup(@RequestHeader(value = SECRET_HEADER, required = false) String provided,
                                    @Valid @RequestBody LookupRequest body) {
        byte[] given = provided == null ? new byte[0] : provided.getBytes(StandardCharsets.UTF_8);
        if (secret.length == 0 || !MessageDigest.isEqual(secret, given)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ErrorResponse.of("UNAUTHORIZED", "Thiếu hoặc sai bí mật thông báo"));
        }
        LookupNotificationRecipientsUseCase.Result result = lookup.execute(body.tenantId(), body.userIds());
        return ResponseEntity.ok(new LookupResponse(result.tenantDisplayName(), result.recipients().stream()
                .map(r -> new RecipientBody(r.userId(), r.email(), r.displayName(), r.active())).toList()));
    }

    @ExceptionHandler(InvalidUserException.class)
    ResponseEntity<ErrorResponse> invalid(InvalidUserException ex) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("VALIDATION_FAILED", ex.getMessage()));
    }
}
