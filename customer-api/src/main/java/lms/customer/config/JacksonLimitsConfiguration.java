package lms.customer.config;

import com.fasterxml.jackson.core.StreamReadConstraints;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Giới hạn đầu vào công khai: JSON lồng tối đa 32 tầng (mặc định Jackson là 1000), chuỗi và cả tài liệu tối đa 16 KB
 * (khớp {@link RequestSizeLimitFilter}), và bộ lọc kích thước thân đặt ngay sau CorrelationIdFilter, trước Spring Security.
 */
@Configuration
public class JacksonLimitsConfiguration {

    static final int MAX_NESTING_DEPTH = 32;

    @Bean
    Jackson2ObjectMapperBuilderCustomizer streamReadLimits() {
        return builder -> builder.postConfigurer(mapper -> mapper.getFactory().setStreamReadConstraints(
                StreamReadConstraints.builder().maxNestingDepth(MAX_NESTING_DEPTH)
                        .maxStringLength((int) RequestSizeLimitFilter.MAX_BODY_BYTES)
                        .maxDocumentLength(RequestSizeLimitFilter.MAX_BODY_BYTES).build()));
    }

    @Bean
    FilterRegistrationBean<RequestSizeLimitFilter> requestSizeLimitFilter(JsonErrorWriter writer) {
        FilterRegistrationBean<RequestSizeLimitFilter> registration =
                new FilterRegistrationBean<>(new RequestSizeLimitFilter(writer));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
