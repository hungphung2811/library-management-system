package lms.identity.user.domain;

import java.time.Instant;
import java.util.UUID;
import lms.common.kernel.DomainEvent;

/**
 * Tóm tắt một lần nhập người dùng từ Excel (ADR-0024): chỉ có số lượng, KHÔNG có tên đăng nhập, email hay mật khẩu.
 * Mỗi lần confirm đúng một sự kiện. Consumer: Audit.
 *
 * @param actorUserId người bấm confirm (sub của JWT)
 * @param completed   false nếu phiên dừng giữa chừng do lỗi
 */
public record UsersImportedEvent(UUID eventId, UUID importId, String actorUserId, int created, int skippedDuplicate,
                                 int failed, boolean completed, Instant occurredAt) implements DomainEvent {

    @Override
    public String aggregateId() {
        return importId.toString();
    }
}
