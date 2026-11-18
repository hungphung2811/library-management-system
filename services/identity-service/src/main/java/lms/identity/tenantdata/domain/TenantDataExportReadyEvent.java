package lms.identity.tenantdata.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.common.kernel.DomainEvent;

/**
 * Gói xuất đã sẵn sàng (topic identity-events, eventType {@code TenantDataExportReady}). Payload CHỈ có id (không email,
 * không đường dẫn, không dữ liệu tenant): support tra địa chỉ người nhận qua API nội bộ như các thông báo khác.
 * eventId tất định theo exportId để Kafka giao lại không nhân bản thông báo.
 */
public record TenantDataExportReadyEvent(UUID eventId, UUID tenantId, UUID exportId, UUID requestedBy,
                                         List<UUID> recipientUserIds, Instant expiresAt, Instant occurredAt)
        implements DomainEvent {

    public static final String TYPE = "TenantDataExportReady";

    public TenantDataExportReadyEvent {
        recipientUserIds = recipientUserIds == null ? List.of() : List.copyOf(recipientUserIds);
    }

    public static TenantDataExportReadyEvent of(TenantDataExport export, List<UUID> recipients, Instant now) {
        UUID id = UUID.nameUUIDFromBytes(("tenant-data-export-ready:" + export.id()).getBytes(StandardCharsets.UTF_8));
        return new TenantDataExportReadyEvent(id, export.tenantId(), export.id(),
                export.requestedByPlatform() ? null : export.requestedBy(), recipients, export.expiresAt(), now);
    }

    @Override
    public String aggregateId() {
        return exportId.toString();
    }

    @Override
    public String eventType() {
        return TYPE;
    }
}
