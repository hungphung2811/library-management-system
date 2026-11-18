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
                p.cardExpiryDate(), p.status(), p.personalInfo()));
        return p;
    }

    @Override
    public Optional<ReaderProfile> findByUserId(UUID userId) {
        return jpa.findById(userId).map(e -> ReaderProfile.restore(e.getUserId(), e.getCardNo(), e.getFullName(),
                e.getEmail(), e.getReaderType(), e.getCardExpiryDate(), e.getStatus(), e.personalInfo()));
    }

    @Override
    public java.util.List<ReaderProfile> findByUserIds(java.util.Collection<UUID> userIds) {
        if (userIds.isEmpty()) return java.util.List.of();
        // @TenantId của Hibernate lọc theo tenant của phiên; RLS lọc thêm ở DB
        return jpa.findAllById(userIds).stream().map(e -> ReaderProfile.restore(e.getUserId(), e.getCardNo(),
                e.getFullName(), e.getEmail(), e.getReaderType(), e.getCardExpiryDate(), e.getStatus(),
                e.personalInfo())).toList();
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

    @Override
    public java.util.Set<String> existingCardNos(java.util.Collection<String> cardNos) {
        java.util.Set<String> found = new java.util.HashSet<>();
        java.util.List<String> all = new java.util.ArrayList<>(cardNos);
        for (int i = 0; i < all.size(); i += 1000) {
            found.addAll(jpa.findCardNosIn(all.subList(i, Math.min(all.size(), i + 1000))));
        }
        return found;
    }
}
