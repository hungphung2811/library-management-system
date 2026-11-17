package lms.circulation.borrowing.infrastructure.config;

import lms.circulation.borrowing.application.BorrowBookUseCase;
import lms.circulation.borrowing.application.CopyInventoryPort;
import lms.circulation.borrowing.application.CopyReservationPort;
import lms.circulation.borrowing.application.FineLedgerPort;
import lms.circulation.borrowing.application.ListBorrowingsUseCase;
import lms.circulation.borrowing.application.ReaderDirectoryPort;
import lms.circulation.borrowing.application.ReportLostUseCase;
import lms.circulation.borrowing.application.SearchBorrowingsUseCase;
import lms.circulation.borrowing.domain.BorrowRuleRepository;
import lms.circulation.borrowing.infrastructure.client.CatalogCopyRestClientAdapter;
import lms.circulation.borrowing.infrastructure.client.IdentityReaderRestClientAdapter;
import lms.circulation.borrowing.infrastructure.integration.FineLedgerAdapter;
import lms.circulation.borrowing.infrastructure.integration.ReservationHoldAdapter;
import lms.circulation.fine.application.AssessFineUseCase;
import lms.circulation.fine.application.GetReaderFinesUseCase;
import lms.circulation.reservation.application.FulfillReservationUseCase;
import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
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

    private static RestClient restClient(String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Bean
    ReaderDirectoryPort readerDirectoryPort(@Value("${app.clients.identity.base-url}") String baseUrl) {
        return new IdentityReaderRestClientAdapter(restClient(baseUrl));
    }

    @Bean
    CopyInventoryPort copyInventoryPort(@Value("${app.clients.catalog.base-url}") String baseUrl) {
        return new CatalogCopyRestClientAdapter(restClient(baseUrl));
    }

    @Bean
    CopyReservationPort copyReservationPort(FulfillReservationUseCase fulfillReservation) {
        return new ReservationHoldAdapter(fulfillReservation);
    }

    @Bean
    FineLedgerPort fineLedgerPort(GetReaderFinesUseCase query, AssessFineUseCase assess) {
        return new FineLedgerAdapter(query, assess);
    }

    @Bean
    BorrowBookUseCase borrowBookUseCase(BorrowingDomainService domainService, BorrowingPersistenceAdapter persistence,
                                        BorrowRuleRepository rules, ReaderDirectoryPort readers,
                                        CopyInventoryPort inventory, CopyReservationPort reservations,
                                        FineLedgerPort fines, KafkaBorrowingEventPublisher publisher, Clock clock) {
        return new BorrowBookUseCase(domainService, persistence, persistence, rules, readers, inventory, reservations,
                fines, publisher, clock);
    }

    @Bean
    ReturnBookUseCase returnBookUseCase(BorrowingPersistenceAdapter persistence, CopyInventoryPort inventory,
                                        FineLedgerPort fines, KafkaBorrowingEventPublisher publisher, Clock clock) {
        return new ReturnBookUseCase(persistence, persistence, inventory, fines, publisher, clock);
    }

    @Bean
    ReportLostUseCase reportLostUseCase(BorrowingPersistenceAdapter persistence, CopyInventoryPort inventory,
                                        FineLedgerPort fines, KafkaBorrowingEventPublisher publisher, Clock clock) {
        return new ReportLostUseCase(persistence, persistence, inventory, fines, publisher, clock);
    }

    @Bean
    ListBorrowingsUseCase listBorrowingsUseCase(BorrowingPersistenceAdapter persistence) {
        return new ListBorrowingsUseCase(persistence);
    }

    @Bean
    SearchBorrowingsUseCase searchBorrowingsUseCase(BorrowingPersistenceAdapter persistence, Clock clock) {
        return new SearchBorrowingsUseCase(persistence, clock);
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
