package lms.circulation.policy.infrastructure.config;

import java.time.Clock;
import lms.circulation.policy.application.GetCirculationPolicyUseCase;
import lms.circulation.policy.application.UpdateCirculationPolicyUseCase;
import lms.circulation.policy.domain.CirculationPolicyRepository;
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
    UpdateCirculationPolicyUseCase updateCirculationPolicyUseCase(CirculationPolicyRepository repository, Clock clock) {
        return new UpdateCirculationPolicyUseCase(repository, clock);
    }
}
