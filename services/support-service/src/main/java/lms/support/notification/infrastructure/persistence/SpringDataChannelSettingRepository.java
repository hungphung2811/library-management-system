package lms.support.notification.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataChannelSettingRepository
        extends JpaRepository<NotificationChannelSettingJpaEntity, NotificationChannelSettingJpaEntity.Key> {
}
