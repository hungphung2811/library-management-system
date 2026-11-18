package lms.circulation.fine.infrastructure.config;

import lms.circulation.fine.application.AssessFineUseCase;
import lms.circulation.fine.application.CopyRestorePort;
import lms.circulation.fine.application.FineAdjustmentUseCases;
import lms.circulation.fine.application.FineRefundUseCases;
import lms.circulation.fine.application.PriceSource;
import lms.circulation.fine.domain.FineAdjustmentRepository;
import lms.circulation.fine.domain.FineRefundRepository;
import lms.circulation.policy.application.GetCompensationPolicyUseCase;
import lms.circulation.policy.application.ResolveDamageLevelUseCase;
import lms.circulation.fine.application.CollectFinesUseCase;
import lms.circulation.fine.application.GetReaderFinesUseCase;
import lms.circulation.fine.domain.FineCalculator;
import lms.circulation.fine.domain.FineRates;
import lms.circulation.fine.domain.FineRepository;
import lms.circulation.fine.infrastructure.messaging.KafkaFineEventPublisher;
import lms.circulation.policy.application.GetCirculationPolicyUseCase;
import lms.messaging.KafkaEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;

/** Composition root của context fine. Mức phạt đọc từ cấu hình (app.fine.*), mặc định theo QĐ3/QĐ4. */
@Configuration
public class FineBeanConfiguration {

    @Bean
    NewTopic fineEventsTopic(@Value("${app.kafka.topics.fine-events}") String name) {
        return TopicBuilder.name(name).partitions(3).replicas(1).build();
    }

    @Bean
    FineRates fineRates(@Value("${app.fine.late-fee-per-day:5000}") BigDecimal lateFeePerDay,
                        @Value("${app.fine.damaged-rate:0.5}") BigDecimal damagedRate,
                        @Value("${app.fine.lost-rate:1.5}") BigDecimal lostRate) {
        return new FineRates(lateFeePerDay, damagedRate, lostRate);
    }

    @Bean
    FineCalculator fineCalculator(FineRates rates) {
        return new FineCalculator(rates);
    }

    @Bean
    KafkaFineEventPublisher kafkaFineEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                                    ObjectMapper objectMapper,
                                                    @Value("${app.kafka.topics.fine-events}") String topic) {
        return new KafkaFineEventPublisher(new KafkaEventPublisher(kafkaTemplate, objectMapper, topic));
    }

    @Bean
    AssessFineUseCase assessFineUseCase(FineCalculator calculator, FineRepository repository,
                                        KafkaFineEventPublisher publisher, Clock clock,
                                        GetCirculationPolicyUseCase policies,
                                        GetCompensationPolicyUseCase compensation, ResolveDamageLevelUseCase levels,
                                        PriceSource prices) {
        return new AssessFineUseCase(calculator, repository, publisher, clock, policies, compensation, levels, prices);
    }

    @Bean
    FineAdjustmentUseCases fineAdjustmentUseCases(FineRepository fines, FineAdjustmentRepository adjustments,
                                                  KafkaFineEventPublisher publisher, Clock clock) {
        return new FineAdjustmentUseCases(fines, adjustments, publisher, clock);
    }

    @Bean
    FineRefundUseCases fineRefundUseCases(FineRepository fines, FineRefundRepository refunds,
                                          GetCompensationPolicyUseCase compensation, CopyRestorePort copies,
                                          KafkaFineEventPublisher publisher, Clock clock) {
        return new FineRefundUseCases(fines, refunds, compensation, copies, publisher, clock);
    }

    @Bean
    CollectFinesUseCase collectFinesUseCase(FineRepository repository, KafkaFineEventPublisher publisher, Clock clock) {
        return new CollectFinesUseCase(repository, publisher, clock);
    }

    @Bean
    GetReaderFinesUseCase getReaderFinesUseCase(FineRepository repository) {
        return new GetReaderFinesUseCase(repository);
    }
}
