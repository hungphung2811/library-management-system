package lms.identity.user.infrastructure.config;

import lms.identity.department.application.GetDepartmentUseCase;
import lms.identity.user.application.ChangeReaderStatusUseCase;
import lms.identity.user.application.ChangeUserStatusUseCase;
import lms.identity.user.application.CreateUserUseCase;
import lms.identity.user.application.DepartmentLookupPort;
import lms.identity.user.application.GetUserDirectoryInfoUseCase;
import lms.identity.user.application.GetUserUseCase;
import lms.identity.user.application.SearchUsersUseCase;
import lms.identity.user.application.UpdateUserUseCase;
import lms.identity.user.domain.UserRepository;
import lms.identity.user.infrastructure.client.InProcessDepartmentLookupAdapter;
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

    @Bean
    DepartmentLookupPort departmentLookupPort(GetDepartmentUseCase getDepartment) {
        return new InProcessDepartmentLookupAdapter(getDepartment);
    }

    @Bean
    CreateUserUseCase createUserUseCase(UserRepository repository, DepartmentLookupPort departments) {
        return new CreateUserUseCase(repository, departments);
    }

    @Bean
    UpdateUserUseCase updateUserUseCase(UserRepository repository, DepartmentLookupPort departments) {
        return new UpdateUserUseCase(repository, departments);
    }

    @Bean
    ChangeUserStatusUseCase changeUserStatusUseCase(UserRepository repository) {
        return new ChangeUserStatusUseCase(repository);
    }

    @Bean
    GetUserUseCase getUserUseCase(UserRepository repository) {
        return new GetUserUseCase(repository);
    }

    @Bean
    SearchUsersUseCase searchUsersUseCase(UserRepository repository) {
        return new SearchUsersUseCase(repository);
    }

    @Bean
    GetUserDirectoryInfoUseCase getUserDirectoryInfoUseCase(UserRepository repository) {
        return new GetUserDirectoryInfoUseCase(repository);
    }
}
