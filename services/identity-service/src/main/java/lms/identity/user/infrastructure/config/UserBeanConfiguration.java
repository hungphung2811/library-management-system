package lms.identity.user.infrastructure.config;

import lms.identity.department.application.GetDepartmentUseCase;
import lms.identity.tenant.application.AddMembershipUseCase;
import lms.identity.tenant.application.ChangeMembershipDepartmentUseCase;
import lms.identity.user.application.AccountCredentialPort;
import lms.identity.user.application.ChangeReaderStatusUseCase;
import lms.identity.user.application.ChangeUserStatusUseCase;
import lms.identity.user.application.CreateUserUseCase;
import lms.identity.user.application.DepartmentLookupPort;
import lms.identity.user.application.GetUserDirectoryInfoUseCase;
import lms.identity.user.application.GetUserUseCase;
import lms.identity.user.application.ResetUserPasswordUseCase;
import lms.identity.user.application.SearchUsersUseCase;
import lms.identity.user.application.TenantMembershipPort;
import lms.identity.user.application.UpdateUserUseCase;
import lms.identity.user.domain.UserRepository;
import lms.identity.user.infrastructure.client.InProcessDepartmentLookupAdapter;
import lms.identity.user.infrastructure.client.InProcessTenantMembershipAdapter;
import lms.identity.user.application.FindReaderUserIdsByEmailUseCase;
import lms.identity.user.application.GetReaderProfileUseCase;
import lms.identity.user.application.IssueReaderCardUseCase;
import lms.identity.user.application.RenewReaderCardUseCase;
import lms.identity.user.domain.ReaderProfileRepository;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composition root của context user. AccountCredentialPort do context auth cung cấp (AuthBeanConfiguration), vì user
 * không được biết auth.
 */
@Configuration
public class UserBeanConfiguration {

    @Bean
    IssueReaderCardUseCase issueReaderCardUseCase(ReaderProfileRepository repository, Clock clock,
                                                  UserRepository users, AccountCredentialPort credentials,
                                                  CreateUserUseCase createUser) {
        return new IssueReaderCardUseCase(repository, clock, users, credentials, createUser);
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
    TenantMembershipPort tenantMembershipPort(AddMembershipUseCase addMembership,
                                              ChangeMembershipDepartmentUseCase changeDepartment) {
        return new InProcessTenantMembershipAdapter(addMembership, changeDepartment);
    }

    @Bean
    lms.identity.user.application.SelfRegisterUserUseCase selfRegisterUserUseCase(CreateUserUseCase createUser) {
        return new lms.identity.user.application.SelfRegisterUserUseCase(createUser);
    }

    @Bean
    CreateUserUseCase createUserUseCase(UserRepository repository, DepartmentLookupPort departments,
                                        AccountCredentialPort credentials, TenantMembershipPort memberships) {
        return new CreateUserUseCase(repository, departments, credentials, memberships);
    }

    /** Tài khoản cho thao tác nền tảng của context tenant (tạo quản trị viên tenant, gắn tài khoản có sẵn). */
    @Bean
    lms.identity.tenant.application.TenantAccountPort tenantAccountPort(CreateUserUseCase createUser,
                                                                        AccountCredentialPort credentials) {
        return new lms.identity.user.infrastructure.client.PlatformTenantAccountAdapter(createUser, credentials);
    }

    @Bean
    UpdateUserUseCase updateUserUseCase(UserRepository repository, DepartmentLookupPort departments,
                                        AccountCredentialPort credentials, TenantMembershipPort memberships) {
        return new UpdateUserUseCase(repository, departments, credentials, memberships);
    }

    @Bean
    ResetUserPasswordUseCase resetUserPasswordUseCase(UserRepository repository, AccountCredentialPort credentials) {
        return new ResetUserPasswordUseCase(repository, credentials);
    }

    @Bean
    ChangeUserStatusUseCase changeUserStatusUseCase(UserRepository repository, AccountCredentialPort credentials) {
        return new ChangeUserStatusUseCase(repository, credentials);
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
