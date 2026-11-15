package lms.circulation.fine.application;

import lms.common.kernel.DomainEvent;

public interface PublishFineEventPort {
    void publish(DomainEvent event);
}
