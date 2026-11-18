package lms.identity.apiclient.domain;

import java.time.Instant;
import java.util.UUID;

/** Một dòng lịch sử chỉ thêm. {@code detail} không bao giờ chứa secret (chỉ hint, scope, origin, lý do). */
public record ApplicationHistoryEntry(UUID id, UUID tenantId, UUID applicationId, UUID credentialId,
                                      ApplicationHistoryAction action, String detail, UUID actorId,
                                      Instant occurredAt) {

    public static final int MAX_DETAIL = 500;

    public static ApplicationHistoryEntry of(UUID tenantId, UUID applicationId, UUID credentialId,
                                             ApplicationHistoryAction action, String detail, UUID actorId,
                                             Instant at) {
        String cut = detail == null ? null : detail.length() > MAX_DETAIL ? detail.substring(0, MAX_DETAIL) : detail;
        return new ApplicationHistoryEntry(UUID.randomUUID(), tenantId, applicationId, credentialId, action, cut,
                actorId, at);
    }
}
