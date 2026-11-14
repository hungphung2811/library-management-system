package lms.identity.auth.application;

import lms.identity.auth.domain.ExternalLogin;
import java.time.Instant;

public record ExternalLoginResponse(String provider, String email, Instant linkedAt, Instant lastUsedAt) {

    public static ExternalLoginResponse from(ExternalLogin l) {
        return new ExternalLoginResponse(l.provider(), l.email(), l.linkedAt(), l.lastUsedAt());
    }
}
