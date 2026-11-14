package lms.identity.auth.application;

import lms.common.kernel.DomainEvent;

public interface PublishAuthEventPort {
    void publish(DomainEvent event);
}
