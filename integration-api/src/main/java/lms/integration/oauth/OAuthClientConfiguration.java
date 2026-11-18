package lms.integration.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import lms.integration.oauth.client.OAuthClient;
import lms.integration.oauth.client.http.HttpOAuthClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Nối cổng OAuthClient với cài đặt HTTP tới identity. */
@Configuration
public class OAuthClientConfiguration {

    @Bean
    OAuthClient oauthClient(RestClient identityRestClient, ObjectMapper mapper) {
        return new HttpOAuthClient(identityRestClient, mapper);
    }
}
