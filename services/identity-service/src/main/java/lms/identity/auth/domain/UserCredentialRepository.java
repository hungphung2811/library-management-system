package lms.identity.auth.domain;

import lms.identity.auth.domain.UserCredential;
import java.util.Optional;
import java.util.UUID;

public interface UserCredentialRepository {
    Optional<UserCredential> findByUsername(String username);

    Optional<UserCredential> findByUserId(UUID userId);

    boolean existsByUserId(UUID userId);

    /** Nhiều credential một lượt (hiển thị trạng thái khóa trong danh sách); id không có credential bị bỏ qua. */
    default java.util.List<UserCredential> findAllByUserIds(java.util.Collection<UUID> userIds) {
        return userIds.stream().map(this::findByUserId).flatMap(Optional::stream).toList();
    }

    boolean existsByUsername(String username);

    /** Tên đăng nhập trong {@code usernames} đã có credential, một truy vấn cho cả lô. */
    default java.util.Set<String> existingUsernames(java.util.Collection<String> usernames) {
        java.util.Set<String> found = new java.util.HashSet<>();
        for (String u : usernames) if (existsByUsername(u)) found.add(u);
        return found;
    }
}
