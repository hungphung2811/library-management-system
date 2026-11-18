package lms.support.notification.infrastructure.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataPreferenceRepository
        extends JpaRepository<NotificationPreferenceJpaEntity, NotificationPreferenceJpaEntity.Key> {

    List<NotificationPreferenceJpaEntity> findByUserId(UUID userId);
}
