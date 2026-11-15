package lms.identity.tenant.application;

import lms.common.kernel.DomainEvent;

/**
 * Phát event của thao tác nền tảng (vòng đời tenant + audit) lên topic identity-events. Envelope lấy tenantId từ
 * TenantContext nên use case gọi trong {@code TenantContext.runAs(tenantId)}.
 */
public interface PublishPlatformEventPort {
    void publish(DomainEvent event);
}
