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

    /** Hồ sơ theo lô (một truy vấn) trong tenant hiện tại; id không có hồ sơ bị bỏ qua. Mặc định tra từng id; bản JPA ghi đè. */
    default java.util.List<ReaderProfile> findByUserIds(java.util.Collection<UUID> userIds) {
        java.util.List<ReaderProfile> out = new java.util.ArrayList<>();
        for (UUID id : userIds) findByUserId(id).ifPresent(out::add);
        return out;
    }

    /** userId của các hồ sơ có email này (không phân biệt hoa thường). */
    java.util.List<UUID> findUserIdsByEmail(String email);
}
