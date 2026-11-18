package lms.support.notification.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.support.notification.domain.NotificationChannel;
import lms.support.notification.domain.NotificationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataTemplateRepository extends JpaRepository<NotificationTemplateJpaEntity, UUID> {

    Optional<NotificationTemplateJpaEntity> findFirstByTypeAndChannelAndActiveTrue(NotificationType type,
                                                                                    NotificationChannel channel);

    List<NotificationTemplateJpaEntity> findByActiveTrue();

    @Query("select coalesce(max(t.version), 0) from NotificationTemplateJpaEntity t "
            + "where t.type = :type and t.channel = :channel")
    int maxVersion(@Param("type") NotificationType type, @Param("channel") NotificationChannel channel);

    /** HQL: Hibernate thêm điều kiện @TenantId cho cả câu UPDATE; RLS lọc thêm ở DB. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update NotificationTemplateJpaEntity t set t.active = false "
            + "where t.type = :type and t.channel = :channel and t.active = true")
    int deactivate(@Param("type") NotificationType type, @Param("channel") NotificationChannel channel);
}
