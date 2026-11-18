package lms.identity.auth.infrastructure.config;

import lms.identity.auth.application.ChangePasswordUseCase;
import lms.identity.apiclient.application.AuthenticateClientUseCase;
import lms.identity.auth.application.IssueClientTokenUseCase;
import lms.identity.auth.application.IssueStorefrontTokenUseCase;
import lms.identity.auth.infrastructure.client.InProcessClientAuthenticationAdapter;
import lms.identity.auth.infrastructure.client.InProcessStorefrontTenantAdapter;
import lms.identity.tenant.application.ResolveStorefrontTenantUseCase;
import lms.identity.auth.application.GetCredentialInfoUseCase;
import lms.identity.auth.application.ResetCredentialPasswordUseCase;
import lms.identity.auth.infrastructure.client.InProcessAccountCredentialAdapter;
import lms.identity.auth.application.LoginUseCase;
import lms.identity.auth.application.LogoutUseCase;
import lms.identity.auth.application.RefreshTokenUseCase;
import lms.identity.auth.application.RegisterCredentialUseCase;
import lms.identity.auth.application.RevokeUserSessionsUseCase;
import lms.identity.auth.application.SignupUseCase;
import lms.identity.auth.application.SwitchTenantSessionUseCase;
import lms.identity.auth.infrastructure.client.InProcessSignupAccountAdapter;
import lms.identity.auth.infrastructure.client.InProcessSignupRoleAdapter;
import lms.identity.accesscontrol.application.SignupRoleUseCase;
import lms.identity.tenant.application.TransactionalRunner;
import lms.identity.user.application.SelfRegisterUserUseCase;
import lms.identity.auth.application.TokenService;
import lms.identity.auth.application.UserDirectoryPort;
import lms.identity.auth.infrastructure.client.InProcessUserDirectoryAdapter;
import lms.identity.auth.infrastructure.client.InProcessTenantAccessAdapter;
import lms.identity.tenant.application.AddMembershipUseCase;
import lms.identity.tenant.application.ListMyTenantsUseCase;
import lms.identity.tenant.application.ListTenantModulesUseCase;
import lms.identity.tenant.application.MembershipLookupUseCase;
import lms.identity.tenant.application.ResolveLoginTenantUseCase;
import lms.identity.tenant.application.SwitchTenantUseCase;
import lms.identity.tenant.application.VerifyTenantAccessUseCase;
import lms.identity.user.application.GetUserDirectoryInfoUseCase;
import lms.identity.accesscontrol.application.GetUserAccessUseCase;
import lms.identity.auth.infrastructure.client.InProcessUserAccessAdapter;
import lms.identity.auth.infrastructure.messaging.KafkaAuthEventPublisher;
import lms.identity.auth.infrastructure.persistence.AuthPersistenceAdapter;
import lms.identity.auth.infrastructure.persistence.CredentialJpaRepository;
import lms.identity.auth.infrastructure.persistence.RefreshTokenJpaRepository;
import lms.identity.auth.infrastructure.security.BcryptPasswordHasher;
import lms.identity.auth.infrastructure.security.JwtTokenIssuer;
import lms.identity.auth.infrastructure.security.SecureRefreshTokenCodec;
import lms.messaging.KafkaEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.RSAKey;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;

/** Composition root: nối domain/application (không có annotation Spring) với adapter. */
@Configuration
public class AuthBeanConfiguration {

    @Bean
    AuthPersistenceAdapter authPersistenceAdapter(CredentialJpaRepository credentials, RefreshTokenJpaRepository tokens,
                                                  Clock clock) {
        return new AuthPersistenceAdapter(credentials, tokens, clock);
    }

    @Bean
    BcryptPasswordHasher passwordHasher() {
        return new BcryptPasswordHasher();
    }

    @Bean
    SecureRefreshTokenCodec refreshTokenCodec() {
        return new SecureRefreshTokenCodec();
    }

    @Bean
    JwtTokenIssuer tokenIssuer(JwtEncoder encoder, RSAKey rsaKey, Clock clock,
                               @Value("${auth.jwt.issuer}") String issuer,
                               @Value("${auth.jwt.access-token-ttl}") Duration ttl,
                               @Value("${lms.identity.token.staff-audience:false}") boolean staffAudience) {
        return new JwtTokenIssuer(encoder, rsaKey.getKeyID(), issuer, ttl, clock, staffAudience);
    }

    @Bean
    InProcessUserDirectoryAdapter userDirectoryAdapter(GetUserDirectoryInfoUseCase getInfo) {
        return new InProcessUserDirectoryAdapter(getInfo);
    }

    @Bean
    InProcessUserAccessAdapter userAccessAdapter(GetUserAccessUseCase getUserAccess) {
        return new InProcessUserAccessAdapter(getUserAccess);
    }

    @Bean
    KafkaAuthEventPublisher authEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper,
                                               @Value("${app.kafka.topics.auth-events}") String topic) {
        return new KafkaAuthEventPublisher(new KafkaEventPublisher(kafkaTemplate, objectMapper, topic));
    }

    @Bean
    NewTopic authEventsTopic(@Value("${app.kafka.topics.auth-events}") String name) {
        return TopicBuilder.name(name).partitions(3).replicas(1).build();
    }

    @Bean
    InProcessTenantAccessAdapter tenantAccessAdapter(ResolveLoginTenantUseCase resolve, SwitchTenantUseCase switchTenant,
                                                     VerifyTenantAccessUseCase verify, ListMyTenantsUseCase listMine,
                                                     AddMembershipUseCase addMembership,
                                                     MembershipLookupUseCase lookup,
                                                     ListTenantModulesUseCase moduleLookup) {
        return new InProcessTenantAccessAdapter(resolve, switchTenant, verify, listMine, addMembership, lookup,
                moduleLookup);
    }

    @Bean
    InProcessStorefrontTenantAdapter storefrontTenantAdapter(ResolveStorefrontTenantUseCase resolve) {
        return new InProcessStorefrontTenantAdapter(resolve);
    }

    /** Tra site key công khai (Public Site Key, pha P2): auth chỉ gọi tầng application của apiclient. */
    @Bean
    lms.identity.auth.infrastructure.client.InProcessSiteKeyTenantAdapter siteKeyTenantAdapter(
            lms.identity.apiclient.application.ResolveSiteKeyUseCase resolve) {
        return new lms.identity.auth.infrastructure.client.InProcessSiteKeyTenantAdapter(resolve);
    }

    @Bean
    IssueStorefrontTokenUseCase issueStorefrontTokenUseCase(InProcessStorefrontTenantAdapter tenants,
                                                            JwtTokenIssuer issuer,
                                                            @Value("${lms.identity.storefront.token-ttl:PT5M}")
                                                            Duration ttl,
                                                            lms.identity.auth.infrastructure.client.InProcessSiteKeyTenantAdapter siteKeys) {
        return new IssueStorefrontTokenUseCase(tenants, issuer, ttl, siteKeys);
    }

    /** Token endpoint OAuth2 client_credentials (SaaS API Authentication pha I2); TTL mặc định 5 phút (Q10). */
    @Bean
    InProcessClientAuthenticationAdapter clientAuthenticationAdapter(AuthenticateClientUseCase authenticate) {
        return new InProcessClientAuthenticationAdapter(authenticate);
    }

    @Bean
    IssueClientTokenUseCase issueClientTokenUseCase(InProcessClientAuthenticationAdapter clients,
                                                    JwtTokenIssuer issuer,
                                                    @Value("${lms.identity.integration.token-ttl:PT5M}")
                                                    Duration ttl) {
        return new IssueClientTokenUseCase(clients, issuer, ttl);
    }

    /** Tự đăng ký công khai (ADR-0010 B2.1): tenant từ storefront (ACTIVE + SALES), role signup_default. */
    @Bean
    SignupUseCase signupUseCase(InProcessStorefrontTenantAdapter tenants, SelfRegisterUserUseCase selfRegister,
                                SignupRoleUseCase signupRole, TransactionalRunner tx) {
        return new SignupUseCase(tenants, new InProcessSignupAccountAdapter(selfRegister),
                new InProcessSignupRoleAdapter(signupRole), tx);
    }

    @Bean
    TokenService tokenService(InProcessUserAccessAdapter accessClient, JwtTokenIssuer issuer, SecureRefreshTokenCodec codec,
                              AuthPersistenceAdapter persistence, Clock clock, UserDirectoryPort userDirectory,
                              InProcessTenantAccessAdapter tenantAccess,
                              @Value("${auth.refresh-token-ttl}") Duration refreshTtl,
                              @Value("${lms.identity.token.storefront-permissions:ShopController/*,TitleController/lookup,ReservationController/mine,ReservationController/cancel}")
                              List<String> storefrontPermissions) {
        return new TokenService(accessClient, issuer, codec, persistence, clock, refreshTtl, userDirectory, tenantAccess,
                storefrontPermissions);
    }

    @Bean
    LoginUseCase loginUseCase(AuthPersistenceAdapter persistence, BcryptPasswordHasher hasher, TokenService tokenService,
                              KafkaAuthEventPublisher publisher, Clock clock,
                              InProcessTenantAccessAdapter tenantAccess) {
        return new LoginUseCase(persistence, persistence, hasher, tokenService, publisher, clock, tenantAccess);
    }

    @Bean
    RefreshTokenUseCase refreshTokenUseCase(AuthPersistenceAdapter persistence, SecureRefreshTokenCodec codec,
                                            TokenService tokenService, InProcessTenantAccessAdapter tenantAccess,
                                            Clock clock,
                                            @Value("${auth.refresh-reuse-grace:PT30S}") Duration reuseGrace) {
        // Ân hạn dùng lại refresh token (ADR-0017): âm hoặc quá 2 phút là cấu hình sai, từ chối khởi động
        if (reuseGrace.isNegative() || reuseGrace.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalStateException("auth.refresh-reuse-grace phải trong khoảng PT0S đến PT2M, đang là " + reuseGrace);
        }
        return new RefreshTokenUseCase(persistence, persistence, persistence, codec, tokenService, tenantAccess, clock,
                reuseGrace);
    }

    @Bean
    SwitchTenantSessionUseCase switchTenantSessionUseCase(AuthPersistenceAdapter persistence,
                                                          InProcessTenantAccessAdapter tenantAccess,
                                                          SecureRefreshTokenCodec codec,
                                                          TokenService tokenService, Clock clock) {
        return new SwitchTenantSessionUseCase(persistence, persistence, persistence, codec, tenantAccess, tokenService,
                clock);
    }

    @Bean
    LogoutUseCase logoutUseCase(AuthPersistenceAdapter persistence, SecureRefreshTokenCodec codec, Clock clock) {
        return new LogoutUseCase(persistence, persistence, codec, clock);
    }

    @Bean
    RegisterCredentialUseCase registerCredentialUseCase(AuthPersistenceAdapter persistence, BcryptPasswordHasher hasher,
                                                        InProcessTenantAccessAdapter tenantAccess,
                                                        UserDirectoryPort userDirectory) {
        return new RegisterCredentialUseCase(persistence, persistence, hasher, tenantAccess, userDirectory);
    }

    @Bean
    ChangePasswordUseCase changePasswordUseCase(AuthPersistenceAdapter persistence, BcryptPasswordHasher hasher) {
        return new ChangePasswordUseCase(persistence, persistence, persistence, hasher);
    }

    @Bean
    RevokeUserSessionsUseCase revokeUserSessionsUseCase(AuthPersistenceAdapter persistence) {
        return new RevokeUserSessionsUseCase(persistence);
    }

    // ---- Quên mật khẩu, mở khóa, thu hồi phiên theo tenant ----

    @Bean
    lms.identity.auth.infrastructure.persistence.PasswordResetPersistenceAdapter passwordResetPersistenceAdapter(
            lms.identity.auth.infrastructure.persistence.PasswordResetTokenJpaRepository jpa) {
        return new lms.identity.auth.infrastructure.persistence.PasswordResetPersistenceAdapter(jpa);
    }

    @Bean
    lms.identity.auth.infrastructure.messaging.PasswordResetEventPublisher passwordResetEventPublisher(
            KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper,
            @Value("${app.kafka.topics.password-reset-events}") String topic,
            @Value("${lms.identity.password-reset.log-token:false}") boolean logToken) {
        return new lms.identity.auth.infrastructure.messaging.PasswordResetEventPublisher(
                new KafkaEventPublisher(kafkaTemplate, objectMapper, topic), logToken);
    }

    @Bean
    NewTopic passwordResetEventsTopic(@Value("${app.kafka.topics.password-reset-events}") String name) {
        return TopicBuilder.name(name).partitions(1).replicas(1).build();
    }

    @Bean
    lms.identity.auth.application.RequestPasswordResetUseCase requestPasswordResetUseCase(
            AuthPersistenceAdapter persistence,
            lms.identity.auth.infrastructure.persistence.PasswordResetPersistenceAdapter resets,
            SecureRefreshTokenCodec codec, lms.identity.auth.infrastructure.messaging.PasswordResetEventPublisher publisher,
            UserDirectoryPort userDirectory, TransactionalRunner tx, Clock clock) {
        return new lms.identity.auth.application.RequestPasswordResetUseCase(persistence, resets, resets, codec,
                publisher, userDirectory, tx, clock);
    }

    @Bean
    lms.identity.auth.application.ConfirmPasswordResetUseCase confirmPasswordResetUseCase(
            AuthPersistenceAdapter persistence,
            lms.identity.auth.infrastructure.persistence.PasswordResetPersistenceAdapter resets,
            BcryptPasswordHasher hasher, SecureRefreshTokenCodec codec, UserDirectoryPort userDirectory, Clock clock) {
        return new lms.identity.auth.application.ConfirmPasswordResetUseCase(resets, resets, persistence, persistence,
                persistence, hasher, codec, userDirectory, clock);
    }

    @Bean
    lms.identity.auth.application.UnlockCredentialUseCase unlockCredentialUseCase(AuthPersistenceAdapter persistence,
                                                                                  KafkaAuthEventPublisher publisher,
                                                                                  Clock clock) {
        return new lms.identity.auth.application.UnlockCredentialUseCase(persistence, persistence, publisher, clock);
    }

    @Bean
    lms.identity.auth.application.RevokeTenantSessionsUseCase revokeTenantSessionsUseCase(
            AuthPersistenceAdapter persistence, KafkaAuthEventPublisher publisher, Clock clock) {
        return new lms.identity.auth.application.RevokeTenantSessionsUseCase(persistence, publisher, clock);
    }

    // ---- Phiên đa thiết bị (ADR-0017) ----

    @Bean
    lms.identity.auth.application.ListMySessionsUseCase listMySessionsUseCase(
            AuthPersistenceAdapter persistence, InProcessTenantAccessAdapter tenantAccess, Clock clock) {
        return new lms.identity.auth.application.ListMySessionsUseCase(persistence, tenantAccess, clock);
    }

    @Bean
    lms.identity.auth.application.RevokeMySessionUseCase revokeMySessionUseCase(AuthPersistenceAdapter persistence,
                                                                                Clock clock) {
        return new lms.identity.auth.application.RevokeMySessionUseCase(persistence, clock);
    }

    @Bean
    lms.identity.auth.application.RevokeMySessionsUseCase revokeMySessionsUseCase(AuthPersistenceAdapter persistence,
                                                                                  Clock clock) {
        return new lms.identity.auth.application.RevokeMySessionsUseCase(persistence, clock);
    }

    @Bean
    lms.identity.auth.application.ListTenantUserSessionsUseCase listTenantUserSessionsUseCase(
            AuthPersistenceAdapter persistence, KafkaAuthEventPublisher publisher, Clock clock) {
        return new lms.identity.auth.application.ListTenantUserSessionsUseCase(persistence, publisher, clock);
    }

    @Bean
    lms.identity.auth.application.RevokeTenantUserSessionUseCase revokeTenantUserSessionUseCase(
            AuthPersistenceAdapter persistence, KafkaAuthEventPublisher publisher, Clock clock) {
        return new lms.identity.auth.application.RevokeTenantUserSessionUseCase(persistence, publisher, clock);
    }

    @Bean
    lms.identity.auth.application.PurgeExpiredRefreshTokensUseCase purgeExpiredRefreshTokensUseCase(
            AuthPersistenceAdapter persistence, Clock clock,
            @Value("${lms.auth.refresh-cleanup.batch-size:1000}") int batchSize) {
        return new lms.identity.auth.application.PurgeExpiredRefreshTokensUseCase(persistence, clock,
                lms.identity.auth.application.PurgeExpiredRefreshTokensUseCase.DEFAULT_RETENTION, batchSize);
    }

    @Bean
    lms.identity.auth.infrastructure.scheduling.RefreshTokenCleanupJob refreshTokenCleanupJob(
            lms.identity.auth.application.PurgeExpiredRefreshTokensUseCase purge) {
        return new lms.identity.auth.infrastructure.scheduling.RefreshTokenCleanupJob(purge);
    }

    @Bean
    GetCredentialInfoUseCase getCredentialInfoUseCase(AuthPersistenceAdapter persistence) {
        return new GetCredentialInfoUseCase(persistence);
    }

    @Bean
    ResetCredentialPasswordUseCase resetCredentialPasswordUseCase(AuthPersistenceAdapter persistence,
                                                                  BcryptPasswordHasher hasher) {
        return new ResetCredentialPasswordUseCase(persistence, persistence, persistence, hasher);
    }

    /** Cổng credential cho context user (tạo tài khoản, đặt lại mật khẩu, kiểm tra platform admin). */
    @Bean
    InProcessAccountCredentialAdapter accountCredentialAdapter(RegisterCredentialUseCase register,
                                                               GetCredentialInfoUseCase info,
                                                               ResetCredentialPasswordUseCase resetPassword,
                                                               lms.identity.auth.application.UnlockCredentialUseCase unlock,
                                                               lms.identity.auth.application.RevokeTenantSessionsUseCase revoke,
                                                               lms.identity.auth.application.ListTenantUserSessionsUseCase listSessions,
                                                               lms.identity.auth.application.RevokeTenantUserSessionUseCase revokeSession) {
        return new InProcessAccountCredentialAdapter(register, info, resetPassword, unlock, revoke, listSessions,
                revokeSession);
    }
}
