package lms.catalog.inventory.application;

import lms.common.kernel.DomainEvent;

public interface PublishInventoryEventPort {
    void publish(DomainEvent event);
}
