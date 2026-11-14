package lms.identity.auth.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Mã dùng một lần, sống ngắn, để frontend đổi lấy token sau khi quay về từ nhà cung cấp. */
public interface ExternalTicketPort {

    /** @return mã gốc (chỉ trả một lần, hệ thống chỉ giữ băm). */
    String issue(UUID userId, Instant now);

    /** Đổi mã lấy userId; mỗi mã chỉ dùng được một lần và chỉ trong thời hạn. */
    Optional<UUID> redeem(String rawCode, Instant now);
}
