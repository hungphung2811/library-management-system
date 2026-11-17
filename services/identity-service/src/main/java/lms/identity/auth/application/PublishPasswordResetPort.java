package lms.identity.auth.application;

import lms.identity.auth.domain.PasswordResetRequestedEvent;

/** Chuyển token thô cho kênh gửi thư (hiện chưa có dịch vụ gửi thư). Best-effort như mọi event. */
public interface PublishPasswordResetPort {
    void publish(PasswordResetRequestedEvent event);
}
