package lms.identity.tenant.application;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Gắn tài khoản có sẵn vào tenant (đường duy nhất để một tài khoản thuộc nhiều tenant). */
public record AttachUserRequest(@NotNull UUID userId) {
}
