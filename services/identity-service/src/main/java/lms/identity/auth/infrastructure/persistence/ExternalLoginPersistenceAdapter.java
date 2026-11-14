package lms.identity.auth.infrastructure.persistence;

import lms.identity.auth.domain.ExternalLogin;
import lms.identity.auth.domain.ExternalLoginRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class ExternalLoginPersistenceAdapter implements ExternalLoginRepository {

    private final ExternalLoginJpaRepository jpa;

    ExternalLoginPersistenceAdapter(ExternalLoginJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public ExternalLogin save(ExternalLogin l) {
        jpa.save(new ExternalLoginJpaEntity(l.id(), l.userId(), l.provider(), l.subject(), l.email(), l.linkedAt(),
                l.lastUsedAt()));
        return l;
    }

    @Override
    public Optional<ExternalLogin> findByProviderAndSubject(String provider, String subject) {
        return jpa.findByProviderAndSubject(provider, subject).map(ExternalLoginPersistenceAdapter::toDomain);
    }

    @Override
    public Optional<ExternalLogin> findByUserIdAndProvider(UUID userId, String provider) {
        return jpa.findByUserIdAndProvider(userId, provider).map(ExternalLoginPersistenceAdapter::toDomain);
    }

    @Override
    public List<ExternalLogin> findByUserId(UUID userId) {
        return jpa.findByUserIdOrderByLinkedAt(userId).stream().map(ExternalLoginPersistenceAdapter::toDomain).toList();
    }

    @Override
    public void delete(ExternalLogin l) {
        jpa.deleteById(l.id());
    }

    private static ExternalLogin toDomain(ExternalLoginJpaEntity e) {
        return ExternalLogin.restore(e.getId(), e.getUserId(), e.getProvider(), e.getSubject(), e.getEmail(),
                e.getLinkedAt(), e.getLastUsedAt());
    }
}
