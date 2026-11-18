package lms.imports.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import lms.imports.core.ImportLimits;
import lms.imports.core.ImportSessionStore;
import lms.imports.engine.ImportCommitExecutor;
import lms.imports.engine.ImportWorkflow;
import lms.imports.engine.UploadGate;
import lms.imports.redis.RedisImportSessionStore;
import lms.imports.web.ImportActorResolver;
import lms.imports.web.ImportExceptionHandler;
import lms.imports.web.JwtImportActorResolver;
import lms.imports.web.MultipartLimitHandler;
import lms.imports.xlsx.StreamingXlsxReader;
import lms.imports.xlsx.SxssfXlsxWriter;
import lms.imports.xlsx.XlsxReader;
import lms.imports.xlsx.XlsxWriter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Bean của luồng nhập. Store luôn là Redis fail-closed (độc lập {@code lms.cache.enabled}); service không có
 * {@code RedisConnectionFactory} thì store vẫn được tạo nhưng mọi thao tác trả 503 IMPORT_STORAGE_UNAVAILABLE.
 */
@AutoConfiguration(afterName = {"org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
        "org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration"})
@EnableConfigurationProperties(ImportProperties.class)
public class ImportAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ImportLimits importLimits(ImportProperties properties) {
        return properties.toLimits();
    }

    @Bean
    @ConditionalOnMissingBean
    XlsxReader importXlsxReader(ImportLimits limits) {
        return new StreamingXlsxReader(limits);
    }

    @Bean
    @ConditionalOnMissingBean
    XlsxWriter importXlsxWriter(ImportLimits limits) {
        return new SxssfXlsxWriter(limits);
    }

    @Bean
    @ConditionalOnMissingBean(ImportSessionStore.class)
    ImportSessionStore importSessionStore(ObjectProvider<RedisConnectionFactory> factory, ObjectProvider<ObjectMapper> mapper,
                                          ImportLimits limits) {
        return new RedisImportSessionStore(factory.getIfAvailable(), mapper.getIfAvailable(), limits, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean
    UploadGate importUploadGate(ImportLimits limits) {
        return new UploadGate(limits.uploadConcurrency());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    ImportCommitExecutor importCommitExecutor(ImportLimits limits) {
        return new ImportCommitExecutor(limits.commitQueue());
    }

    @Bean
    @ConditionalOnMissingBean
    ImportWorkflow importWorkflow(ImportSessionStore store, XlsxReader reader, XlsxWriter writer, UploadGate gate,
                                  ImportCommitExecutor executor, ImportLimits limits) {
        return new ImportWorkflow(store, reader, writer, gate, executor, limits, Clock.systemUTC());
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @Import({ImportExceptionHandler.class, MultipartLimitHandler.class})
    static class WebConfiguration {

        @Bean
        @ConditionalOnMissingBean
        ImportActorResolver importActorResolver() {
            return new JwtImportActorResolver();
        }
    }
}
