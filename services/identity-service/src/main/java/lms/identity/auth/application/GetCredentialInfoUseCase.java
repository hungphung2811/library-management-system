package lms.identity.auth.application;

import lms.identity.auth.domain.UserCredential;
import lms.identity.auth.domain.UserCredentialRepository;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * API của context auth cho context khác (user): thông tin công khai của credential, không có mật khẩu. credentials là
 * bảng toàn cục nên kết quả không phụ thuộc tenant hiện tại.
 */
public class GetCredentialInfoUseCase {

    private final UserCredentialRepository repository;

    public GetCredentialInfoUseCase(UserCredentialRepository repository) {
        this.repository = repository;
    }

    public Optional<CredentialInfo> byUserId(UUID userId) {
        return repository.findByUserId(userId).map(GetCredentialInfoUseCase::info);
    }

    /** Tên đăng nhập so khớp sau khi chuẩn hóa (trim, chữ thường) như lúc tạo credential. */
    public Optional<CredentialInfo> byUsername(String username) {
        String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        return repository.findByUsername(normalized).map(GetCredentialInfoUseCase::info);
    }

    /** Tên đăng nhập (đã chuẩn hóa) đã có credential, một truy vấn cho cả lô (nhập hàng loạt). */
    public java.util.Set<String> existingUsernames(java.util.Collection<String> usernames) {
        if (usernames.isEmpty()) return java.util.Set.of();
        return repository.existingUsernames(usernames);
    }

    /** Thời điểm hết khóa của những người đang bị khóa tại {@code now} (người không bị khóa không có trong map). */
    public java.util.Map<UUID, java.time.Instant> activeLocks(java.util.Collection<UUID> userIds, java.time.Instant now) {
        java.util.Map<UUID, java.time.Instant> result = new java.util.HashMap<>();
        if (userIds.isEmpty()) return result;
        for (UserCredential c : repository.findAllByUserIds(new java.util.HashSet<>(userIds))) {
            if (c.isLocked(now)) result.put(c.userId(), c.lockedUntil());
        }
        return result;
    }

    private static CredentialInfo info(UserCredential c) {
        return new CredentialInfo(c.userId(), c.username(), c.platformAdmin());
    }

    public record CredentialInfo(UUID userId, String username, boolean platformAdmin) {
    }
}
