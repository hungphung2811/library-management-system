package lms.support.notification.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import lms.support.notification.application.CreateNotificationUseCase;
import lms.support.notification.application.DeliveryTransactions;
import lms.support.notification.application.DeliveryUseCases;
import lms.support.notification.application.DispatchNotificationsUseCase;
import lms.support.notification.application.EnqueueNotificationUseCase;
import lms.support.notification.application.NotificationProvider;
import lms.support.notification.application.NotificationProviderRegistry;
import lms.support.notification.application.NotificationSettingsUseCases;
import lms.support.notification.application.NotificationTemplateUseCases;
import lms.support.notification.application.RecipientDirectoryPort;
import lms.support.notification.application.TemplateResolver;
import lms.support.notification.domain.RetryPolicy;
import lms.support.notification.infrastructure.client.HttpRecipientDirectory;
import lms.support.notification.infrastructure.email.ClasspathDefaultTemplates;
import lms.support.notification.infrastructure.email.EmailProvider;
import lms.support.notification.infrastructure.persistence.DeliveryPersistenceAdapter;
import lms.support.notification.infrastructure.persistence.NotificationConfigPersistenceAdapter;
import lms.support.notification.infrastructure.persistence.SpringDataChannelSettingRepository;
import lms.support.notification.infrastructure.persistence.SpringDataDeliveryRepository;
import lms.support.notification.infrastructure.persistence.SpringDataPreferenceRepository;
import lms.support.notification.infrastructure.persistence.SpringDataTemplateRepository;
import lms.support.notification.infrastructure.persistence.SpringDataTenantRegistryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.client.RestClient;
import lms.support.notification.application.CountUnreadNotificationsUseCase;
import lms.support.notification.application.ListMyNotificationsUseCase;
import lms.support.notification.application.MarkAllNotificationsReadUseCase;
import lms.support.notification.application.MarkNotificationReadUseCase;
import lms.support.notification.infrastructure.persistence.NotificationPersistenceAdapter;
import lms.support.notification.infrastructure.persistence.SpringDataNotificationRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root của ngữ cảnh notification (Clock dùng chung từ AuditBeanConfiguration). */
@Configuration
public class NotificationBeanConfiguration {

    @Bean
    NotificationPersistenceAdapter notificationPersistenceAdapter(SpringDataNotificationRepository jpa) {
        return new NotificationPersistenceAdapter(jpa);
    }

    @Bean
    CreateNotificationUseCase createNotificationUseCase(NotificationPersistenceAdapter persistence, Clock clock) {
        return new CreateNotificationUseCase(persistence, clock);
    }

    @Bean
    ListMyNotificationsUseCase listMyNotificationsUseCase(NotificationPersistenceAdapter persistence) {
        return new ListMyNotificationsUseCase(persistence);
    }

    @Bean
    MarkNotificationReadUseCase markNotificationReadUseCase(NotificationPersistenceAdapter persistence, Clock clock) {
        return new MarkNotificationReadUseCase(persistence, clock);
    }

    @Bean
    MarkAllNotificationsReadUseCase markAllNotificationsReadUseCase(NotificationPersistenceAdapter persistence,
                                                                    Clock clock) {
        return new MarkAllNotificationsReadUseCase(persistence, clock);
    }

    @Bean
    CountUnreadNotificationsUseCase countUnreadNotificationsUseCase(NotificationPersistenceAdapter persistence) {
        return new CountUnreadNotificationsUseCase(persistence);
    }

    // ---- Thông báo theo sự kiện + provider (ADR-0030, gói C) ----

    @Bean
    DeliveryPersistenceAdapter deliveryPersistenceAdapter(SpringDataDeliveryRepository jpa, ObjectMapper mapper) {
        return new DeliveryPersistenceAdapter(jpa, mapper);
    }

    @Bean
    NotificationConfigPersistenceAdapter notificationConfigPersistenceAdapter(
            SpringDataTemplateRepository templates, SpringDataChannelSettingRepository channels,
            SpringDataPreferenceRepository preferences, SpringDataTenantRegistryRepository registry) {
        return new NotificationConfigPersistenceAdapter(templates, channels, preferences, registry);
    }

    @Bean
    ClasspathDefaultTemplates classpathDefaultTemplates() {
        return new ClasspathDefaultTemplates();
    }

    @Bean
    TemplateResolver templateResolver(NotificationConfigPersistenceAdapter config, ClasspathDefaultTemplates defaults) {
        return new TemplateResolver(config, defaults);
    }

    /** Provider EMAIL (SMTP). Thêm kênh mới = thêm một bean NotificationProvider, registry tự nhận. */
    @Bean
    EmailProvider emailProvider(ObjectProvider<JavaMailSender> sender,
                                @Value("${lms.notification.email.from:no-reply@lms.local}") String from) {
        return new EmailProvider(sender, from);
    }

    @Bean
    NotificationProviderRegistry notificationProviderRegistry(List<NotificationProvider> providers) {
        return new NotificationProviderRegistry(providers);
    }

    @Bean
    EnqueueNotificationUseCase enqueueNotificationUseCase(NotificationPersistenceAdapter inApp,
                                                          DeliveryPersistenceAdapter deliveries,
                                                          NotificationConfigPersistenceAdapter config,
                                                          NotificationProviderRegistry providers,
                                                          TemplateResolver templates, Clock clock) {
        return new EnqueueNotificationUseCase(inApp, deliveries, config, config, config, providers, templates, clock);
    }

    @Bean
    DeliveryTransactions deliveryTransactions(DeliveryPersistenceAdapter deliveries) {
        return new DeliveryTransactions(deliveries);
    }

    @Bean
    RestClient identityRecipientRestClient(
            @Value("${lms.security.permission.identity-base-url:http://localhost:8081}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Bean
    RecipientDirectoryPort recipientDirectory(RestClient identityRecipientRestClient,
                                              @Value("${lms.notification.client-secret:}") String secret) {
        return new HttpRecipientDirectory(identityRecipientRestClient, secret);
    }

    @Bean
    DispatchNotificationsUseCase dispatchNotificationsUseCase(
            DeliveryTransactions tx, NotificationConfigPersistenceAdapter config, NotificationProviderRegistry providers,
            RecipientDirectoryPort directory, TemplateResolver templates, Clock clock,
            @Value("${lms.notification.batch-size:50}") int batchSize,
            @Value("${lms.notification.rate.per-tenant-per-minute:60}") int perTenant,
            @Value("${lms.notification.rate.global-per-minute:600}") int global,
            @Value("${lms.notification.stuck-after:PT5M}") Duration stuckAfter,
            @Value("${lms.notification.retry.delays:PT1M,PT5M,PT30M,PT2H,PT6H}") List<Duration> retryDelays) {
        return new DispatchNotificationsUseCase(tx, config, providers, directory, templates,
                new DispatchNotificationsUseCase.Settings(batchSize, perTenant, global, stuckAfter,
                        new RetryPolicy(retryDelays)), clock);
    }

    @Bean
    DeliveryUseCases deliveryUseCases(DeliveryPersistenceAdapter deliveries, Clock clock) {
        return new DeliveryUseCases(deliveries, clock);
    }

    @Bean
    NotificationSettingsUseCases notificationSettingsUseCases(NotificationConfigPersistenceAdapter config, Clock clock) {
        return new NotificationSettingsUseCases(config, config, clock);
    }

    @Bean
    NotificationTemplateUseCases notificationTemplateUseCases(NotificationConfigPersistenceAdapter config,
                                                              ClasspathDefaultTemplates defaults,
                                                              TemplateResolver resolver, Clock clock) {
        return new NotificationTemplateUseCases(config, defaults, resolver, clock);
    }
}
