package lms.circulation.reservation.infrastructure.config;

import lms.circulation.reservation.application.CancelReservationUseCase;
import lms.circulation.reservation.application.ExpireOverdueReservationsUseCase;
import lms.circulation.reservation.application.HoldCopyForNextReservationUseCase;
import lms.circulation.reservation.application.ListMyReservationsUseCase;
import lms.circulation.reservation.application.ReaderEligibilityPort;
import lms.circulation.reservation.application.ReserveTitleUseCase;
import lms.circulation.reservation.application.TitleCatalogPort;
import lms.circulation.reservation.domain.ReservationRepository;
import lms.circulation.reservation.infrastructure.client.CatalogRestClientAdapter;
import lms.circulation.reservation.infrastructure.client.IdentityRestClientAdapter;
import lms.circulation.reservation.infrastructure.messaging.KafkaReservationEventPublisher;
import lms.messaging.KafkaEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

/** Composition root của context reservation. */
@Configuration
@EnableScheduling
public class ReservationBeanConfiguration {

    private static RestClient restClient(String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Bean
    NewTopic reservationEventsTopic(@Value("${app.kafka.topics.reservation-events}") String name) {
        return TopicBuilder.name(name).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic inventoryEventsTopic(@Value("${app.kafka.topics.inventory-events}") String name) {
        return TopicBuilder.name(name).partitions(3).replicas(1).build();
    }

    @Bean
    TitleCatalogPort titleCatalogPort(@Value("${app.clients.catalog.base-url}") String baseUrl) {
        return new CatalogRestClientAdapter(restClient(baseUrl));
    }

    @Bean
    ReaderEligibilityPort readerEligibilityPort(@Value("${app.clients.identity.base-url}") String baseUrl) {
        return new IdentityRestClientAdapter(restClient(baseUrl));
    }

    @Bean
    KafkaReservationEventPublisher kafkaReservationEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                                                  ObjectMapper objectMapper,
                                                                  @Value("${app.kafka.topics.reservation-events}") String topic) {
        return new KafkaReservationEventPublisher(new KafkaEventPublisher(kafkaTemplate, objectMapper, topic));
    }

    @Bean
    ReserveTitleUseCase reserveTitleUseCase(ReaderEligibilityPort eligibility, TitleCatalogPort catalog,
                                            ReservationRepository repository, KafkaReservationEventPublisher publisher,
                                            Clock clock) {
        return new ReserveTitleUseCase(eligibility, catalog, repository, publisher, clock);
    }

    @Bean
    HoldCopyForNextReservationUseCase holdCopyForNextReservationUseCase(ReservationRepository repository,
                                                                        KafkaReservationEventPublisher publisher,
                                                                        Clock clock) {
        return new HoldCopyForNextReservationUseCase(repository, publisher, clock);
    }

    @Bean
    CancelReservationUseCase cancelReservationUseCase(ReservationRepository repository,
                                                      HoldCopyForNextReservationUseCase holdCopy,
                                                      KafkaReservationEventPublisher publisher, Clock clock) {
        return new CancelReservationUseCase(repository, holdCopy, publisher, clock);
    }

    @Bean
    ExpireOverdueReservationsUseCase expireOverdueReservationsUseCase(ReservationRepository repository,
                                                                      HoldCopyForNextReservationUseCase holdCopy,
                                                                      KafkaReservationEventPublisher publisher,
                                                                      Clock clock) {
        return new ExpireOverdueReservationsUseCase(repository, holdCopy, publisher, clock);
    }

    @Bean
    ListMyReservationsUseCase listMyReservationsUseCase(ReservationRepository repository) {
        return new ListMyReservationsUseCase(repository);
    }
}
