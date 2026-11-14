package lms.identity.auth.application;

import java.util.List;
import java.util.UUID;

public interface TokenIssuerPort {

    IssuedToken issue(Claims claims);

    record Claims(UUID userId, String username, UUID departmentId, List<String> roles, List<String> permissions) {
    }

    record IssuedToken(String value, long expiresInSeconds) {
    }
}
