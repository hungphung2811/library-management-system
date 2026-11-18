package lms.imports.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import lms.imports.core.ImportErrorCode;
import lms.imports.core.ImportException;
import lms.imports.core.ImportLimits;
import lms.imports.core.ImportSessionStore;
import lms.imports.core.SessionKey;
import lms.imports.engine.ImportWorkflow;
import lms.imports.redis.RedisImportSessionStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ImportAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ImportAutoConfiguration.class));

    @Test
    void registeredInAutoConfigurationImports() throws Exception {
        try (var in = getClass().getResourceAsStream("/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")) {
            assertThat(new String(in.readAllBytes())).contains("lms.imports.autoconfigure.ImportAutoConfiguration");
        }
    }

    @Test
    void withoutRedisTheStoreExistsButFailsClosed() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(ImportWorkflow.class);
            ImportSessionStore store = ctx.getBean(ImportSessionStore.class);
            assertThat(store).isInstanceOf(RedisImportSessionStore.class);
            assertThatThrownBy(() -> store.findMeta(new SessionKey(UUID.randomUUID(), "u", UUID.randomUUID())))
                    .isInstanceOfSatisfying(ImportException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ImportErrorCode.STORAGE_UNAVAILABLE));
        });
    }

    @Test
    void independentFromCacheSwitchAndHonoursProperties() {
        runner.withPropertyValues("lms.cache.enabled=false", "lms.import.max-rows=10", "lms.import.session-ttl=5m")
                .run(ctx -> {
                    ImportLimits limits = ctx.getBean(ImportLimits.class);
                    assertThat(limits.maxRows()).isEqualTo(10);
                    assertThat(limits.sessionTtl()).hasMinutes(5);
                    assertThat(limits.maxFileBytes()).isEqualTo(5L * 1024 * 1024);
                    assertThat(ctx).hasSingleBean(ImportSessionStore.class);
                });
    }

    @Test
    void defaultsMatchTheAdr() {
        ImportLimits d = ImportLimits.defaults();
        assertThat(d.maxRows()).isEqualTo(5000);
        assertThat(d.maxFileBytes()).isEqualTo(5L << 20);
        assertThat(d.sessionTtl()).hasMinutes(30);
        assertThat(d.maxSessionsPerUser()).isEqualTo(3);
        assertThat(d.chunkSize()).isEqualTo(200);
        assertThat(d.maxCellChars()).isEqualTo(2000);
    }
}
