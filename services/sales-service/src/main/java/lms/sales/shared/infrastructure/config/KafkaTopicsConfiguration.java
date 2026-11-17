package lms.sales.shared.infrastructure.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Khai báo topic sales.events để KafkaAdmin của Spring Boot tạo khi khởi động (cùng khuôn circulation).
 * KafkaAdmin mặc định không làm hỏng khởi động khi broker chưa sẵn sàng (fatalIfBrokerNotAvailable=false).
 */
@Configuration
public class KafkaTopicsConfiguration {

    @Bean
    NewTopic salesEventsTopic(@Value("${app.kafka.topics.sales-events}") String name,
                              @Value("${app.kafka.topic-partitions:3}") int partitions,
                              @Value("${app.kafka.topic-replicas:1}") short replicas) {
        return TopicBuilder.name(name).partitions(partitions).replicas(replicas).build();
    }
}
