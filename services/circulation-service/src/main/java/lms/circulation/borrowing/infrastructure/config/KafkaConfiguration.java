package lms.circulation.borrowing.infrastructure.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfiguration {

    @Bean
    NewTopic borrowingEventsTopic(@Value("${app.kafka.topics.borrowing-events}") String name) {
        return TopicBuilder.name(name).partitions(3).replicas(1).build();
    }
}
