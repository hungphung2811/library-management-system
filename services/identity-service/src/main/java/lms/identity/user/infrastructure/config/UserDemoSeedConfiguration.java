package lms.identity.user.infrastructure.config;

import lms.identity.user.application.CreateUserUseCase;
import lms.identity.user.application.EnsureReaderCardUseCase;
import lms.identity.user.application.EnsureUserUseCase;
import lms.identity.user.application.IssueReaderCardUseCase;
import lms.identity.user.domain.ReaderProfileRepository;
import lms.identity.user.domain.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Bean phục vụ seed dữ liệu demo (ADR-0018); chỉ tồn tại khi profile {@code demo-seed} bật. */
@Configuration
@Profile("demo-seed")
class UserDemoSeedConfiguration {

    @Bean
    EnsureUserUseCase ensureUserUseCase(UserRepository repository, CreateUserUseCase createUser) {
        return new EnsureUserUseCase(repository, createUser);
    }

    @Bean
    EnsureReaderCardUseCase ensureReaderCardUseCase(ReaderProfileRepository profiles, IssueReaderCardUseCase issueCard) {
        return new EnsureReaderCardUseCase(profiles, issueCard);
    }
}
