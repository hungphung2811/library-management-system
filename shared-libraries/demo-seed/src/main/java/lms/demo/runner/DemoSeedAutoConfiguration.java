package lms.demo.runner;

import java.util.Arrays;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * Chỉ tồn tại khi profile {@code demo-seed} bật, nên khởi động bình thường không có bean nào của module này.
 * Không dùng {@code @ConditionalOnProperty(enabled)}: thiếu cờ thì guard từ chối rõ ràng (mã thoát 2) thay vì im lặng.
 */
@AutoConfiguration
@Profile("demo-seed")
@EnableConfigurationProperties(DemoSeedProperties.class)
public class DemoSeedAutoConfiguration {

    @Bean
    DemoSeedRunner demoSeedRunner(DemoSeedProperties properties, ObjectProvider<DemoSeedStep> steps,
                                  Environment environment, ApplicationContext context) {
        return DemoSeedRunner.forApplication(properties, Arrays.asList(environment.getActiveProfiles()),
                steps.orderedStream().toList(), context);
    }
}
