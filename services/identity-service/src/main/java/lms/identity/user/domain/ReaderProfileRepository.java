package lms.identity.user.domain;

import java.util.Optional;
import java.util.UUID;

public interface ReaderProfileRepository {

    ReaderProfile save(ReaderProfile profile);

    Optional<ReaderProfile> findByUserId(UUID userId);

    boolean existsByUserId(UUID userId);

    boolean existsByCardNo(String cardNo);

    /** userId của các hồ sơ có email này (không phân biệt hoa thường). */
    java.util.List<UUID> findUserIdsByEmail(String email);
}
