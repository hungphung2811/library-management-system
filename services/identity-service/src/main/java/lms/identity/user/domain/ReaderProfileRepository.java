package lms.identity.user.domain;

import java.util.Optional;
import java.util.UUID;

public interface ReaderProfileRepository {

    ReaderProfile save(ReaderProfile profile);

    Optional<ReaderProfile> findByUserId(UUID userId);

    boolean existsByUserId(UUID userId);

    boolean existsByCardNo(String cardNo);

    /** Số thẻ trong {@code cardNos} đã có (trong tenant hiện tại, theo RLS); một truy vấn cho cả lô nhập. */
    default java.util.Set<String> existingCardNos(java.util.Collection<String> cardNos) {
        java.util.Set<String> found = new java.util.HashSet<>();
        for (String c : cardNos) if (existsByCardNo(c)) found.add(c);
        return found;
    }

    /** userId của các hồ sơ có email này (không phân biệt hoa thường). */
    java.util.List<UUID> findUserIdsByEmail(String email);
}
