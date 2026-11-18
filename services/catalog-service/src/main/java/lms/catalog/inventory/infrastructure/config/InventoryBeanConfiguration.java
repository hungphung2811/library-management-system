package lms.catalog.inventory.infrastructure.config;

import lms.catalog.inventory.application.AddCopyUseCase;
import lms.catalog.inventory.application.ChangeCopyStatusUseCase;
import lms.catalog.inventory.application.CopyAvailabilityQuery;
import lms.catalog.inventory.application.RegisterCopiesInBatchUseCase;
import lms.catalog.inventory.application.GetCopyUseCase;
import lms.catalog.inventory.application.HoldCopyUseCase;
import lms.catalog.inventory.application.ListCopiesUseCase;
import lms.catalog.inventory.application.MoveCopyShelfLocationUseCase;
import lms.catalog.inventory.application.ReleaseCopyUseCase;
import lms.catalog.inventory.application.UpdateCopyAcquisitionUseCase;
import lms.catalog.inventory.domain.BookCopyRepository;
import lms.catalog.inventory.infrastructure.messaging.KafkaInventoryEventPublisher;
import lms.messaging.KafkaEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.ZoneId;
import lms.catalog.inventory.application.LookupCopiesUseCase;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;

/** Composition root của context inventory. */
@Configuration
public class InventoryBeanConfiguration {

    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Bean
    Clock clock() {
        // Mốc thời gian nghiệp vụ theo giờ Việt Nam (ADR-0018), không phụ thuộc múi giờ máy/container (UTC).
        return Clock.system(BUSINESS_ZONE);
    }

    @Bean
    LookupCopiesUseCase lookupCopiesUseCase(BookCopyRepository repository) {
        return new LookupCopiesUseCase(repository);
    }

    @Bean
    NewTopic inventoryEventsTopic(@Value("${app.kafka.topics.inventory-events}") String name) {
        return TopicBuilder.name(name).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic reservationEventsTopic(@Value("${app.kafka.topics.reservation-events}") String name) {
        return TopicBuilder.name(name).partitions(3).replicas(1).build();
    }

    @Bean
    KafkaInventoryEventPublisher kafkaInventoryEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                                              ObjectMapper objectMapper,
                                                              @Value("${app.kafka.topics.inventory-events}") String topic) {
        return new KafkaInventoryEventPublisher(new KafkaEventPublisher(kafkaTemplate, objectMapper, topic));
    }

    @Bean
    AddCopyUseCase addCopyUseCase(BookCopyRepository repository, KafkaInventoryEventPublisher publisher, Clock clock) {
        return new AddCopyUseCase(repository, publisher, clock);
    }

    @Bean
    RegisterCopiesInBatchUseCase registerCopiesInBatchUseCase(BookCopyRepository repository,
                                                              KafkaInventoryEventPublisher publisher, Clock clock) {
        return new RegisterCopiesInBatchUseCase(repository, publisher, clock);
    }

    @Bean
    MoveCopyShelfLocationUseCase moveCopyShelfLocationUseCase(BookCopyRepository repository) {
        return new MoveCopyShelfLocationUseCase(repository);
    }

    @Bean
    UpdateCopyAcquisitionUseCase updateCopyAcquisitionUseCase(BookCopyRepository repository) {
        return new UpdateCopyAcquisitionUseCase(repository);
    }

    @Bean
    HoldCopyUseCase holdCopyUseCase(BookCopyRepository repository) {
        return new HoldCopyUseCase(repository);
    }

    @Bean
    ReleaseCopyUseCase releaseCopyUseCase(BookCopyRepository repository, KafkaInventoryEventPublisher publisher,
                                          Clock clock) {
        return new ReleaseCopyUseCase(repository, publisher, clock);
    }

    @Bean
    ListCopiesUseCase listCopiesUseCase(BookCopyRepository repository) {
        return new ListCopiesUseCase(repository);
    }

    @Bean
    GetCopyUseCase getCopyUseCase(BookCopyRepository repository) {
        return new GetCopyUseCase(repository);
    }

    @Bean
    ChangeCopyStatusUseCase changeCopyStatusUseCase(BookCopyRepository repository,
                                                    KafkaInventoryEventPublisher publisher, Clock clock) {
        return new ChangeCopyStatusUseCase(repository, publisher, clock);
    }

    @Bean
    CopyAvailabilityQuery copyAvailabilityQuery(BookCopyRepository repository) {
        return new CopyAvailabilityQuery(repository);
    }
}
