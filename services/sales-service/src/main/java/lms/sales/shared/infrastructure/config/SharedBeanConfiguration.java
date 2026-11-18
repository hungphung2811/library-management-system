package lms.sales.shared.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import lms.messaging.KafkaEventPublisher;
import lms.sales.shared.application.PersonalDataVisibility;
import lms.sales.shared.application.SalesAuditQueryPort;
import lms.sales.shared.application.SalesAuditQueryUseCase;
import lms.sales.shared.infrastructure.messaging.SalesEventPublisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

/** Bean dùng chung: đồng hồ và bộ phát sales.events. */
@Configuration
public class SharedBeanConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** lms.sales.events.enabled=false tắt phát Kafka (IT, môi trường không có broker). */
    @Bean
    SalesEventPublisher salesEventPublisher(ObjectProvider<KafkaTemplate<String, String>> kafkaTemplate,
                                            ObjectMapper objectMapper,
                                            @Value("${app.kafka.topics.sales-events}") String topic,
                                            @Value("${lms.sales.events.enabled:true}") boolean enabled) {
        KafkaTemplate<String, String> template = enabled ? kafkaTemplate.getIfAvailable() : null;
        return new SalesEventPublisher(template == null ? null
                : new KafkaEventPublisher(template, objectMapper, topic));
    }

    /**
     * lms.privacy.mask-personal-data (mặc định true): false trả dữ liệu cá nhân đầy đủ ở API nhân viên (chỉ dev).
     * Prod phải giữ true.
     */
    @Bean
    PersonalDataPolicyInitializer personalDataPolicyInitializer(
            @Value("${lms.privacy.mask-personal-data:true}") boolean maskPersonalData) {
        PersonalDataVisibility.configure(maskPersonalData);
        return new PersonalDataPolicyInitializer(maskPersonalData);
    }

    record PersonalDataPolicyInitializer(boolean maskPersonalData) {
    }

    @Bean
    SalesAuditQueryUseCase salesAuditQueryUseCase(SalesAuditQueryPort port) {
        return new SalesAuditQueryUseCase(port);
    }
}
