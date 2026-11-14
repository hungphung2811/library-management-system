package lms.identity.auth.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExternalLoginRepository {

    ExternalLogin save(ExternalLogin login);

    Optional<ExternalLogin> findByProviderAndSubject(String provider, String subject);

    Optional<ExternalLogin> findByUserIdAndProvider(UUID userId, String provider);

    List<ExternalLogin> findByUserId(UUID userId);

    void delete(ExternalLogin login);
}
