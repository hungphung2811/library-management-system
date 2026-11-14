package lms.identity.auth.infrastructure.config;

import lms.identity.auth.application.ExchangeExternalTicketUseCase;
import lms.identity.auth.application.ExternalSignInUseCase;
import lms.identity.auth.application.ExternalTicketPort;
import lms.identity.auth.application.LinkExternalLoginUseCase;
import lms.identity.auth.application.ListExternalLoginsUseCase;
import lms.identity.auth.application.ReaderLookupPort;
import lms.identity.auth.application.TokenService;
import lms.identity.auth.application.UnlinkExternalLoginUseCase;
import lms.identity.auth.domain.ExternalLoginRepository;
import lms.identity.auth.domain.UserCredentialRepository;
import lms.identity.auth.infrastructure.client.InProcessReaderLookupAdapter;
import lms.identity.auth.infrastructure.security.ExternalProviderRegistry;
import lms.identity.auth.infrastructure.security.OidcClient;
import lms.identity.auth.infrastructure.security.Pkce;
import lms.identity.auth.infrastructure.security.ReturnUrlValidator;
import lms.identity.auth.infrastructure.security.StateCookieCodec;
import lms.identity.user.application.FindReaderUserIdsByEmailUseCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Composition root của đăng nhập ngoài (OIDC). */
@Configuration
@EnableConfigurationProperties(ExternalAuthProperties.class)
public class ExternalAuthBeanConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ExternalAuthBeanConfiguration.class);

    @Bean
    ExternalProviderRegistry externalProviderRegistry(ExternalAuthProperties properties) {
        return new ExternalProviderRegistry(properties);
    }

    @Bean
    ReturnUrlValidator returnUrlValidator(ExternalAuthProperties properties) {
        return new ReturnUrlValidator(properties.getAllowedReturnOrigins());
    }

    @Bean
    StateCookieCodec stateCookieCodec(ExternalAuthProperties properties, ObjectMapper mapper) {
        String configured = properties.getStateSecret();
        byte[] secret;
        if (configured == null || configured.isBlank()) {
            log.warn("auth.external.state-secret để trống: dùng khóa tạm, lượt đăng nhập ngoài đang dở sẽ hỏng khi restart "
                    + "và nhiều instance không dùng chung được");
            secret = Pkce.randomToken(32).getBytes(StandardCharsets.US_ASCII);
        } else {
            secret = configured.getBytes(StandardCharsets.UTF_8);
        }
        return new StateCookieCodec(mapper, secret);
    }

    @Bean
    OidcClient oidcClient(Clock clock) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return new OidcClient(RestClient.builder().requestFactory(factory).build(), clock);
    }

    @Bean
    ReaderLookupPort readerLookupPort(FindReaderUserIdsByEmailUseCase findByEmail) {
        return new InProcessReaderLookupAdapter(findByEmail);
    }

    @Bean
    ExternalSignInUseCase externalSignInUseCase(ExternalLoginRepository logins, UserCredentialRepository credentials,
                                                ReaderLookupPort readers, ExternalProviderRegistry registry,
                                                ExternalTicketPort tickets, Clock clock) {
        return new ExternalSignInUseCase(logins, credentials, readers, registry, tickets, clock);
    }

    @Bean
    LinkExternalLoginUseCase linkExternalLoginUseCase(ExternalLoginRepository logins, UserCredentialRepository credentials,
                                                      Clock clock) {
        return new LinkExternalLoginUseCase(logins, credentials, clock);
    }

    @Bean
    UnlinkExternalLoginUseCase unlinkExternalLoginUseCase(ExternalLoginRepository logins) {
        return new UnlinkExternalLoginUseCase(logins);
    }

    @Bean
    ListExternalLoginsUseCase listExternalLoginsUseCase(ExternalLoginRepository logins) {
        return new ListExternalLoginsUseCase(logins);
    }

    @Bean
    ExchangeExternalTicketUseCase exchangeExternalTicketUseCase(ExternalTicketPort tickets,
                                                                UserCredentialRepository credentials,
                                                                TokenService tokenService, Clock clock) {
        return new ExchangeExternalTicketUseCase(tickets, credentials, tokenService, clock);
    }
}
