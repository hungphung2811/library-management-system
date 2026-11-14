package lms.identity.accesscontrol.application;

import lms.common.kernel.DomainEvent;

public interface PublishAccessEventPort {
    void publish(DomainEvent event);
}
