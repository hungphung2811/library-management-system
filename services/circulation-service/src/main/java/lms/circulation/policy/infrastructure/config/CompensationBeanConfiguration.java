package lms.circulation.policy.infrastructure.config;

import java.time.Clock;
import lms.cache.RequestCache;
import lms.circulation.policy.application.CreateDamageLevelUseCase;
import lms.circulation.policy.application.DamageLevelUsagePort;
import lms.circulation.policy.application.DeleteDamageLevelUseCase;
import lms.circulation.policy.application.GetCompensationPolicyUseCase;
import lms.circulation.policy.application.ListDamageLevelsUseCase;
import lms.circulation.policy.application.ResolveDamageLevelUseCase;
import lms.circulation.policy.application.UpdateCompensationPolicyUseCase;
import lms.circulation.policy.application.UpdateDamageLevelUseCase;
import lms.circulation.policy.domain.CompensationPolicyRepository;
import lms.circulation.policy.domain.DamageLevelRepository;
import lms.circulation.policy.infrastructure.cache.CachedCompensationReader;
import lms.circulation.policy.infrastructure.cache.EvictingCompensationUseCases;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root của chính sách đền bù và bậc hỏng theo tenant (cùng context policy). */
@Configuration
public class CompensationBeanConfiguration {

    /** Bean gốc (đọc DB, không cache): lập phạt, hoàn khoản và mọi nghiệp vụ dùng bean này. */
    @Bean
    GetCompensationPolicyUseCase getCompensationPolicyUseCase(CompensationPolicyRepository repository) {
        return new GetCompensationPolicyUseCase(repository);
    }

    @Bean
    UpdateCompensationPolicyUseCase updateCompensationPolicyUseCase(CompensationPolicyRepository repository, Clock clock,
                                                                    RequestCache cache) {
        return new EvictingCompensationUseCases.UpdatePolicy(repository, clock, cache);
    }

    @Bean
    ListDamageLevelsUseCase listDamageLevelsUseCase(DamageLevelRepository repository) {
        return new ListDamageLevelsUseCase(repository);
    }

    @Bean
    CreateDamageLevelUseCase createDamageLevelUseCase(DamageLevelRepository repository, Clock clock, RequestCache cache) {
        return new EvictingCompensationUseCases.CreateLevel(repository, clock, cache);
    }

    @Bean
    UpdateDamageLevelUseCase updateDamageLevelUseCase(DamageLevelRepository repository, Clock clock, RequestCache cache) {
        return new EvictingCompensationUseCases.UpdateLevel(repository, clock, cache);
    }

    @Bean
    DeleteDamageLevelUseCase deleteDamageLevelUseCase(DamageLevelRepository repository, DamageLevelUsagePort usage,
                                                      RequestCache cache) {
        return new EvictingCompensationUseCases.DeleteLevel(repository, usage, cache);
    }

    /** Bean gốc, dùng khi lập khoản hỏng (không cache). */
    @Bean
    ResolveDamageLevelUseCase resolveDamageLevelUseCase(DamageLevelRepository repository) {
        return new ResolveDamageLevelUseCase(repository);
    }

    /** Chỉ cho các GET của controller; nghiệp vụ lập phạt/hoàn khoản inject use case gốc ở trên. */
    @Bean
    CachedCompensationReader cachedCompensationReader(GetCompensationPolicyUseCase policy, ListDamageLevelsUseCase levels,
                                                      RequestCache cache) {
        return new CachedCompensationReader(policy, levels, cache);
    }
}
