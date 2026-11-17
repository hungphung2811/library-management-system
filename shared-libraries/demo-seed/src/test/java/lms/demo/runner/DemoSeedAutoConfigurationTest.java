package lms.demo.runner;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DemoSeedAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DemoSeedAutoConfiguration.class));

    @Test
    void khoiDongBinhThuongKhongCoRunner() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(DemoSeedRunner.class));
    }

    @Test
    void chiCoRunnerKhiProfileDemoSeedBat() {
        runner.withPropertyValues("spring.profiles.active=demo-seed")
                .run(ctx -> assertThat(ctx).hasSingleBean(DemoSeedRunner.class));
    }
}
