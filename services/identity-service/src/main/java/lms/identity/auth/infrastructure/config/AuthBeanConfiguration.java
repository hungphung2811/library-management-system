package lms.identity.auth.infrastructure.config;

import lms.identity.auth.application.LoginUseCase;
import lms.identity.auth.application.LogoutUseCase;
import lms.identity.auth.application.RefreshTokenUseCase;
import lms.identity.auth.application.RegisterCredentialUseCase;
import lms.identity.auth.application.RevokeUserSessionsUseCase;
import lms.identity.auth.application.TokenService;
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
    AuthPersistenceAdapter authPersistenceAdapter(CredentialJpaRepository credentials, RefreshTokenJpaRepository tokens) {
        return new AuthPersistenceAdapter(credentials, tokens);
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
                               @Value("${auth.jwt.access-token-ttl}") Duration ttl) {
        return new JwtTokenIssuer(encoder, rsaKey.getKeyID(), issuer, ttl, clock);
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
    TokenService tokenService(InProcessUserAccessAdapter accessClient, JwtTokenIssuer issuer, SecureRefreshTokenCodec codec,
                              AuthPersistenceAdapter persistence, Clock clock,
                              @Value("${auth.refresh-token-ttl}") Duration refreshTtl) {
        return new TokenService(accessClient, issuer, codec, persistence, clock, refreshTtl);
    }

    @Bean
    LoginUseCase loginUseCase(AuthPersistenceAdapter persistence, BcryptPasswordHasher hasher, TokenService tokenService,
                              KafkaAuthEventPublisher publisher, Clock clock) {
        return new LoginUseCase(persistence, persistence, hasher, tokenService, publisher, clock);
    }

    @Bean
    RefreshTokenUseCase refreshTokenUseCase(AuthPersistenceAdapter persistence, SecureRefreshTokenCodec codec,
                                            TokenService tokenService, Clock clock) {
        return new RefreshTokenUseCase(persistence, persistence, persistence, codec, tokenService, clock);
    }

    @Bean
    LogoutUseCase logoutUseCase(AuthPersistenceAdapter persistence, SecureRefreshTokenCodec codec) {
        return new LogoutUseCase(persistence, persistence, codec);
    }

    @Bean
    RegisterCredentialUseCase registerCredentialUseCase(AuthPersistenceAdapter persistence, BcryptPasswordHasher hasher) {
        return new RegisterCredentialUseCase(persistence, persistence, hasher);
    }

    @Bean
    RevokeUserSessionsUseCase revokeUserSessionsUseCase(AuthPersistenceAdapter persistence) {
        return new RevokeUserSessionsUseCase(persistence);
    }
}
