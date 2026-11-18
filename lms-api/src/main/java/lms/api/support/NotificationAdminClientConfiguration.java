package lms.api.support;

import lms.api.config.ServiceClients;
import lms.api.support.client.NotificationAdminClient;
import lms.api.support.client.http.HttpNotificationAdminClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Client API thông báo theo sự kiện (ADR-0030) của support-service, tách khỏi SupportClient để hai bên đổi độc lập. */
@Configuration
public class NotificationAdminClientConfiguration {

    @Bean
    NotificationAdminClient notificationAdminClient(ServiceClients clients) {
        return new HttpNotificationAdminClient(clients.create(clients.urls().supportUrl()));
    }
}
