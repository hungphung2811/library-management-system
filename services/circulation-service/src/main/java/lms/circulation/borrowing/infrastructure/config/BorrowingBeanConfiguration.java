package lms.circulation.borrowing.infrastructure.config;

import lms.circulation.borrowing.application.BorrowBookUseCase;
import lms.circulation.borrowing.application.GetBorrowingUseCase;
import lms.circulation.borrowing.application.ProvisionDefaultBorrowRulesUseCase;
import lms.circulation.borrowing.infrastructure.persistence.BorrowRuleJdbcAdapter;
import lms.circulation.borrowing.application.RenewBorrowingUseCase;
import lms.circulation.borrowing.application.ReturnBookUseCase;
import lms.circulation.borrowing.domain.BorrowingDomainService;
import lms.circulation.borrowing.infrastructure.messaging.KafkaBorrowingEventPublisher;
import lms.circulation.borrowing.infrastructure.persistence.BorrowingPersistenceAdapter;
import lms.messaging.KafkaEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

/** Composition root: nối domain/application (không có annotation Spring) với adapter. */
@Configuration
public class BorrowingBeanConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    BorrowingDomainService borrowingDomainService() {
        return new BorrowingDomainService();
    }

    @Bean
    KafkaBorrowingEventPublisher kafkaBorrowingEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                                              ObjectMapper objectMapper,
                                                              @Value("${app.kafka.topics.borrowing-events}") String topic) {
        return new KafkaBorrowingEventPublisher(new KafkaEventPublisher(kafkaTemplate, objectMapper, topic));
    }

    @Bean
    BorrowBookUseCase borrowBookUseCase(BorrowingDomainService domainService, BorrowingPersistenceAdapter persistence,
                                        KafkaBorrowingEventPublisher publisher, Clock clock) {
        return new BorrowBookUseCase(domainService, persistence, persistence, publisher, clock);
    }

    @Bean
    ReturnBookUseCase returnBookUseCase(BorrowingPersistenceAdapter persistence,
                                        KafkaBorrowingEventPublisher publisher, Clock clock) {
        return new ReturnBookUseCase(persistence, persistence, publisher, clock);
    }

    @Bean
    RenewBorrowingUseCase renewBorrowingUseCase(BorrowingPersistenceAdapter persistence, Clock clock) {
        return new RenewBorrowingUseCase(persistence, persistence, clock);
    }

    @Bean
    GetBorrowingUseCase getBorrowingUseCase(BorrowingPersistenceAdapter persistence) {
        return new GetBorrowingUseCase(persistence);
    }

    @Bean
    ProvisionDefaultBorrowRulesUseCase provisionDefaultBorrowRulesUseCase(BorrowRuleJdbcAdapter rules) {
        return new ProvisionDefaultBorrowRulesUseCase(rules);
    }
}
