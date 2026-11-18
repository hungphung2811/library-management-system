package lms.identity.auth.application;

import java.util.Optional;
import java.util.UUID;

/** Cổng sang context user: tra người dùng theo email và email theo người dùng (toàn cục, không theo tenant). */
public interface UserContactPort {

    Optional<UUID> userIdByEmail(String email);

    Optional<String> emailOf(UUID userId);
}
