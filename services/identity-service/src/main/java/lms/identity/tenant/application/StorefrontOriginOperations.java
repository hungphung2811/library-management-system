package lms.identity.tenant.application;

import java.time.Instant;
import java.util.UUID;
import lms.identity.tenant.domain.PlatformOperationEvent;
import lms.identity.tenant.domain.StorefrontOriginAction;
import lms.identity.tenant.domain.StorefrontOriginEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Phần chung của ba use case ghi: phát event audit nền tảng và log {@code platform-audit} sau commit (best-effort). */
final class StorefrontOriginOperations {

    private static final Logger AUDIT = LoggerFactory.getLogger("platform-audit");

    private StorefrontOriginOperations() {
    }

    static Instant now() {
        return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    static void announce(PublishPlatformEventPort events, StorefrontOriginAction action, UUID actor,
                         StorefrontOriginEntry entry) {
        events.publish(PlatformOperationEvent.of("STOREFRONT_ORIGIN_" + action.name(), actor, entry.tenantId(),
                entry.id(), "origin=" + entry.origin()));
        AUDIT.info("platform-audit operation=storefront-origin-{} actor={} tenant={} originId={} origin={}",
                action.name().toLowerCase(java.util.Locale.ROOT), actor, entry.tenantId(), entry.id(), entry.origin());
    }
}
