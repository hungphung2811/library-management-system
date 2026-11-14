package lms.identity.user.infrastructure.persistence;

import lms.identity.user.domain.ReaderProfile;
import lms.identity.user.domain.ReaderProfileRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class ReaderProfilePersistenceAdapter implements ReaderProfileRepository {

    private final SpringDataReaderProfileRepository jpa;

    ReaderProfilePersistenceAdapter(SpringDataReaderProfileRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public ReaderProfile save(ReaderProfile p) {
        jpa.save(new ReaderProfileJpaEntity(p.userId(), p.cardNo(), p.fullName(), p.email(), p.readerType(),
                p.cardExpiryDate(), p.status()));
        return p;
    }

    @Override
    public Optional<ReaderProfile> findByUserId(UUID userId) {
        return jpa.findById(userId).map(e -> ReaderProfile.restore(e.getUserId(), e.getCardNo(), e.getFullName(),
                e.getEmail(), e.getReaderType(), e.getCardExpiryDate(), e.getStatus()));
    }

    @Override
    public boolean existsByUserId(UUID userId) {
        return jpa.existsById(userId);
    }

    @Override
    public java.util.List<UUID> findUserIdsByEmail(String email) {
        return jpa.findUserIdsByEmail(email);
    }

    @Override
    public boolean existsByCardNo(String cardNo) {
        return jpa.existsByCardNo(cardNo);
    }
}
