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
import lms.identity.user.application.UpdateReaderProfileUseCase;
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
    UpdateReaderProfileUseCase updateReaderProfileUseCase(ReaderProfileRepository repository, Clock clock) {
        return new UpdateReaderProfileUseCase(repository, clock);
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
    lms.identity.user.application.GetUserContactUseCase getUserContactUseCase(
            lms.identity.user.domain.UserRepository repository) {
        return new lms.identity.user.application.GetUserContactUseCase(repository);
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
    lms.identity.user.application.UserLockStatus userLockStatus(AccountCredentialPort credentials, Clock clock) {
        return new lms.identity.user.application.UserLockStatus(credentials, clock);
    }

    @Bean
    UpdateUserUseCase updateUserUseCase(UserRepository repository, DepartmentLookupPort departments,
                                        AccountCredentialPort credentials, TenantMembershipPort memberships,
                                        lms.identity.user.application.UserLockStatus lockStatus) {
        return new UpdateUserUseCase(repository, departments, credentials, memberships, lockStatus);
    }

    @Bean
    lms.identity.user.application.UnlockUserUseCase unlockUserUseCase(UserRepository repository,
                                                                      AccountCredentialPort credentials) {
        return new lms.identity.user.application.UnlockUserUseCase(repository, credentials);
    }

    @Bean
    lms.identity.user.application.RevokeUserSessionsInTenantUseCase revokeUserSessionsInTenantUseCase(
            UserRepository repository, AccountCredentialPort credentials,
            lms.identity.user.application.TargetAdminLookupPort adminLookup) {
        return new lms.identity.user.application.RevokeUserSessionsInTenantUseCase(repository, credentials,
                adminLookup);
    }

    @Bean
    lms.identity.user.application.ListUserSessionsUseCase listUserSessionsUseCase(
            UserRepository repository, AccountCredentialPort credentials,
            lms.identity.user.application.TargetAdminLookupPort adminLookup) {
        return new lms.identity.user.application.ListUserSessionsUseCase(repository, credentials, adminLookup);
    }

    @Bean
    lms.identity.user.application.RevokeUserSessionUseCase revokeUserSessionUseCase(
            UserRepository repository, AccountCredentialPort credentials,
            lms.identity.user.application.TargetAdminLookupPort adminLookup) {
        return new lms.identity.user.application.RevokeUserSessionUseCase(repository, credentials, adminLookup);
    }

    @Bean
    lms.identity.user.application.ExportUsersUseCase exportUsersUseCase(UserRepository repository,
                                                                       DepartmentLookupPort departments,
                                                                       lms.identity.user.application.UserRoleLookupPort roles) {
        return new lms.identity.user.application.ExportUsersUseCase(repository, departments, roles);
    }

    /** Bean riêng để mỗi dòng nhập đi qua proxy giao dịch (một transaction mỗi dòng). */
    @Bean
    lms.identity.user.application.ImportUserRowExecutor importUserRowExecutor(CreateUserUseCase createUser,
                                                                              IssueReaderCardUseCase issueReaderCard,
                                                                              Clock clock) {
        return new lms.identity.user.application.ImportUserRowExecutor(createUser, issueReaderCard, clock);
    }

    /** Không @Transactional: transaction nằm ở từng dòng (ImportUserRowExecutor). */
    @Bean
    lms.identity.user.application.ImportUsersUseCase importUsersUseCase(
            UserRepository repository, AccountCredentialPort credentials, DepartmentLookupPort departments,
            lms.identity.user.application.ImportUserRowExecutor executor) {
        return new lms.identity.user.application.ImportUsersUseCase(repository, credentials, departments, executor);
    }

    // ---- Nhập người dùng từ Excel (ADR-0024) ----

    /** Bean riêng để mỗi dòng ghi đi qua proxy giao dịch (một transaction mỗi dòng). */
    @Bean
    lms.identity.user.application.UserImportRowWriter userImportRowWriter(CreateUserUseCase createUser,
                                                                          IssueReaderCardUseCase issueReaderCard) {
        return new lms.identity.user.application.UserImportRowWriter(createUser, issueReaderCard);
    }

    @Bean
    lms.identity.user.application.UserImportAuditPort userImportAuditPort(
            org.springframework.kafka.core.KafkaTemplate<String, String> kafkaTemplate,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Value("${app.kafka.topics.auth-events}") String topic) {
        return new lms.identity.user.infrastructure.messaging.KafkaUserImportAuditAdapter(
                new lms.messaging.KafkaEventPublisher(kafkaTemplate, objectMapper, topic));
    }

    @Bean
    lms.identity.user.application.UserImportHandler userImportHandler(
            UserRepository users, AccountCredentialPort credentials, ReaderProfileRepository readers,
            lms.identity.user.application.UserImportRowWriter writer,
            lms.identity.user.application.UserImportAuditPort audit, Clock clock) {
        return new lms.identity.user.application.UserImportHandler(users, credentials, readers, writer,
                new lms.identity.user.application.TemporaryPasswordGenerator(), audit, clock);
    }

    @Bean
    ResetUserPasswordUseCase resetUserPasswordUseCase(UserRepository repository, AccountCredentialPort credentials) {
        return new ResetUserPasswordUseCase(repository, credentials);
    }

    @Bean
    ChangeUserStatusUseCase changeUserStatusUseCase(UserRepository repository, AccountCredentialPort credentials,
                                                    lms.identity.user.application.UserLockStatus lockStatus) {
        return new ChangeUserStatusUseCase(repository, credentials, lockStatus);
    }

    @Bean
    GetUserUseCase getUserUseCase(UserRepository repository, lms.identity.user.application.UserLockStatus lockStatus) {
        return new GetUserUseCase(repository, lockStatus);
    }

    @Bean
    SearchUsersUseCase searchUsersUseCase(UserRepository repository,
                                          lms.identity.user.application.UserLockStatus lockStatus) {
        return new SearchUsersUseCase(repository, lockStatus);
    }

    @Bean
    lms.identity.user.application.LookupUsersUseCase lookupUsersUseCase(UserRepository repository) {
        return new lms.identity.user.application.LookupUsersUseCase(repository);
    }

    /** Tra email + tên hiển thị người nhận thông báo cho support-service (ADR-0030 D5). */
    @Bean
    lms.identity.user.application.LookupNotificationRecipientsUseCase lookupNotificationRecipientsUseCase(
            UserRepository users, ReaderProfileRepository readers,
            lms.identity.tenant.application.TenantNameQuery tenantNames,
            lms.identity.tenant.application.TransactionalRunner tenantTransactionalRunner) {
        return new lms.identity.user.application.LookupNotificationRecipientsUseCase(users, readers, tenantNames,
                tenantTransactionalRunner);
    }

    @Bean
    GetUserDirectoryInfoUseCase getUserDirectoryInfoUseCase(UserRepository repository) {
        return new GetUserDirectoryInfoUseCase(repository);
    }
}
