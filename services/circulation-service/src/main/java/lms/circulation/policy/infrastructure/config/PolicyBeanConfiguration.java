package lms.circulation.policy.infrastructure.config;

import java.time.Clock;
import lms.cache.RequestCache;
import lms.circulation.policy.application.GetCirculationPolicyUseCase;
import lms.circulation.policy.application.UpdateCirculationPolicyUseCase;
import lms.circulation.policy.domain.CirculationPolicyRepository;
import lms.circulation.policy.infrastructure.cache.CachedPolicyReader;
import lms.circulation.policy.infrastructure.cache.EvictingUpdatePolicyUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root của context policy (chính sách mượn trả theo tenant). */
@Configuration
public class PolicyBeanConfiguration {

    @Bean
    GetCirculationPolicyUseCase getCirculationPolicyUseCase(CirculationPolicyRepository repository) {
        return new GetCirculationPolicyUseCase(repository);
    }

    @Bean
    UpdateCirculationPolicyUseCase updateCirculationPolicyUseCase(CirculationPolicyRepository repository, Clock clock,
                                                                  RequestCache cache) {
        return new EvictingUpdatePolicyUseCase(repository, clock, cache);
    }

    /** Chỉ cho GET /internal/circulation/policy; nghiệp vụ mượn trả vẫn inject GetCirculationPolicyUseCase (không cache). */
    @Bean
    CachedPolicyReader cachedPolicyReader(GetCirculationPolicyUseCase getPolicy, RequestCache cache) {
        return new CachedPolicyReader(getPolicy, cache);
    }
}
