package lms.identity.contract.application;

import java.util.ArrayList;
import java.util.List;
import lms.common.kernel.DomainEvent;
import lms.identity.tenant.application.PublishPlatformEventPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Gom event trong transaction, phát SAU commit (best-effort, ADR-0001). */
final class EventBuffer {

    private static final Logger log = LoggerFactory.getLogger(EventBuffer.class);

    private final List<DomainEvent> events = new ArrayList<>();

    void add(DomainEvent event) {
        events.add(event);
    }

    int size() {
        return events.size();
    }

    void publishTo(PublishPlatformEventPort port) {
        for (DomainEvent e : events) {
            try {
                port.publish(e);
            } catch (RuntimeException ex) {
                // best-effort: lịch sử trong DB mới là nguồn sự thật
                log.error("Không phát được event {}: {}", e.eventType(), ex.toString());
            }
        }
        events.clear();
    }
}
