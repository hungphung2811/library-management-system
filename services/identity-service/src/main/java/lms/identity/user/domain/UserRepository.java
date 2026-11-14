package lms.identity.user.domain;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository {

    /** Lưu và flush ngay; vi phạm unique (username, email) ném DuplicateUserException. */
    User save(User user);

    Optional<User> findById(UUID id);

    boolean existsById(UUID id);

    boolean existsByUsername(String username);

    /** Email đã được người dùng khác dùng (bỏ qua chính excludeId nếu có), không phân biệt hoa thường. */
    boolean existsByEmail(String email, UUID excludeId);

    UserPage search(UserCriteria criteria);
}
