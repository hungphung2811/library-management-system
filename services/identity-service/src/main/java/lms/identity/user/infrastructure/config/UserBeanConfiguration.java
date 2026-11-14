package lms.identity.user.infrastructure.config;

import lms.identity.user.application.ChangeReaderStatusUseCase;
import lms.identity.user.application.FindReaderUserIdsByEmailUseCase;
import lms.identity.user.application.GetReaderProfileUseCase;
import lms.identity.user.application.IssueReaderCardUseCase;
import lms.identity.user.application.RenewReaderCardUseCase;
import lms.identity.user.domain.ReaderProfileRepository;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root của context user. */
@Configuration
public class UserBeanConfiguration {

    @Bean
    IssueReaderCardUseCase issueReaderCardUseCase(ReaderProfileRepository repository, Clock clock) {
        return new IssueReaderCardUseCase(repository, clock);
    }

    @Bean
    GetReaderProfileUseCase getReaderProfileUseCase(ReaderProfileRepository repository, Clock clock) {
        return new GetReaderProfileUseCase(repository, clock);
    }

    @Bean
    RenewReaderCardUseCase renewReaderCardUseCase(ReaderProfileRepository repository, Clock clock) {
        return new RenewReaderCardUseCase(repository, clock);
    }

    @Bean
    ChangeReaderStatusUseCase changeReaderStatusUseCase(ReaderProfileRepository repository, Clock clock) {
        return new ChangeReaderStatusUseCase(repository, clock);
    }

    @Bean
    FindReaderUserIdsByEmailUseCase findReaderUserIdsByEmailUseCase(ReaderProfileRepository repository) {
        return new FindReaderUserIdsByEmailUseCase(repository);
    }
}
