package lms.circulation.borrowing.domain;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

/**
 * eventId tất định của sự kiện nhắc hạn: cùng (tenant, phiếu, loại, hạn trả, lần nhắc) luôn cho cùng một UUID, nên giao
 * lại hoặc chạy lại job không sinh thư thứ hai (support khử trùng theo eventId, ADR-0030 D11).
 */
public final class LoanReminderIds {

    private LoanReminderIds() {
    }

    public static UUID eventId(UUID tenantId, UUID borrowingId, LoanReminderKind kind, LocalDate dueDate, int seq) {
        String name = tenantId + ":" + borrowingId + ":" + kind + ":" + dueDate + ":" + seq;
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }
}
