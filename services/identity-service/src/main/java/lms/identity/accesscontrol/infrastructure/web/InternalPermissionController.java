package lms.identity.accesscontrol.infrastructure.web;

import lms.common.kernel.ErrorResponse;
import lms.identity.accesscontrol.application.AccessCommands.RegisterPermissionKey;
import lms.identity.accesscontrol.application.RegisterPermissionKeyUseCase;
import lms.security.permission.EndpointDescriptor;
import lms.security.permission.PublicEndpoint;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API nội bộ để các service báo khóa quyền của endpoint khi được gọi lần đầu. Không route qua gateway, bảo vệ bằng khóa
 * chia sẻ trong header. Chưa cấu hình khóa thì từ chối mọi lời gọi (đóng theo mặc định).
 */
@PublicEndpoint
@RestController
@RequestMapping("/internal/permissions")
public class InternalPermissionController {

    private final RegisterPermissionKeyUseCase register;
    private final Clock clock;
    private final byte[] apiKey;

    public InternalPermissionController(RegisterPermissionKeyUseCase register, Clock clock,
                                        @Value("${app.internal-api-key:}") String apiKey) {
        this.register = register;
        this.clock = clock;
        this.apiKey = apiKey.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/registrations")
    public ResponseEntity<?> register(@RequestHeader(value = EndpointDescriptor.INTERNAL_KEY_HEADER, required = false)
                                      String provided,
                                      @RequestBody EndpointDescriptor body) {
        byte[] given = provided == null ? new byte[0] : provided.getBytes(StandardCharsets.UTF_8);
        if (apiKey.length == 0 || !MessageDigest.isEqual(apiKey, given)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ErrorResponse.of("UNAUTHORIZED", "Thiếu hoặc sai khóa nội bộ"));
        }
        register.execute(new RegisterPermissionKey(body.service(), body.key(), body.source(), body.httpMethod(),
                body.pathPattern(), body.description(), clock.instant()));
        return ResponseEntity.noContent().build();
    }
}
