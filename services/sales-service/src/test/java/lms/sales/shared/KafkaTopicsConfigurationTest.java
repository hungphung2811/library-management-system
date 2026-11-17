package lms.sales.shared;

import static org.assertj.core.api.Assertions.assertThat;

import lms.sales.shared.infrastructure.config.KafkaTopicsConfiguration;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class KafkaTopicsConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(KafkaTopicsConfiguration.class);

    @Test
    void taoTopicVoiMacDinhBaPartitionMotReplica() {
        runner.withPropertyValues("app.kafka.topics.sales-events=sales.events").run(ctx -> {
            NewTopic topic = ctx.getBean(NewTopic.class);
            assertThat(topic.name()).isEqualTo("sales.events");
            assertThat(topic.numPartitions()).isEqualTo(3);
            assertThat(topic.replicationFactor()).isEqualTo((short) 1);
        });
    }

    @Test
    void docTenVaSoPartitionTuCauHinh() {
        runner.withPropertyValues("app.kafka.topics.sales-events=x.events", "app.kafka.topic-partitions=6",
                "app.kafka.topic-replicas=2").run(ctx -> {
            NewTopic topic = ctx.getBean(NewTopic.class);
            assertThat(topic.name()).isEqualTo("x.events");
            assertThat(topic.numPartitions()).isEqualTo(6);
            assertThat(topic.replicationFactor()).isEqualTo((short) 2);
        });
    }
}
