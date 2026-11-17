package lms.identity.contract.domain;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Mốc nhắc đã ghi; PK (contract, days_before, ends_at) chống gửi trùng giữa nhiều instance. */
public interface ContractReminderRepository {

    /** Các mốc ngày đã có dòng cho (hợp đồng, ends_at). */
    Set<Integer> recordedDays(UUID contractId, Instant endsAt);

    /** Ghi dòng mốc. @return false nếu dòng đã tồn tại (instance khác đã ghi). */
    boolean record(UUID contractId, int daysBefore, Instant endsAt, int recipientCount, boolean skipped, Instant now);
}
