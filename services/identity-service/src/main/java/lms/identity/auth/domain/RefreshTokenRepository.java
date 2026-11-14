package lms.identity.auth.domain;

import lms.identity.auth.domain.RefreshToken;
import java.util.Optional;

public interface RefreshTokenRepository {
    Optional<RefreshToken> findByTokenHash(String tokenHash);
}
