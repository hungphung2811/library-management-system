package lms.api.identity;

import lms.api.config.ServicesProperties;
import lms.api.identity.client.AccessClient;
import lms.api.identity.client.AuthClient;
import lms.api.identity.client.DepartmentClient;
import lms.api.identity.client.IdentityGatewayClient;
import lms.api.identity.client.UserClient;
import lms.api.identity.client.http.HttpIdentityGatewayClient;
import lms.api.identity.client.http.HttpAccessClient;
import lms.api.identity.client.http.HttpAuthClient;
import lms.api.identity.client.http.HttpDepartmentClient;
import lms.api.identity.client.http.HttpUserClient;
import lms.api.support.TokenRelayInterceptor;
import java.io.IOException;
import java.net.HttpURLConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Client của identity dùng RestClient KHÔNG tự follow redirect: luồng đăng nhập ngoài cần nhận nguyên 302 (Location,
 * Set-Cookie) của service để chuyển cho trình duyệt. Các lời gọi còn lại không có redirect nên dùng chung cấu hình này.
 */
@Configuration
public class IdentityClientConfiguration {

    /** HttpURLConnection mặc định follow redirect với GET; tắt hẳn. */
    static final class NoRedirectRequestFactory extends SimpleClientHttpRequestFactory {
        @Override
        protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
            super.prepareConnection(connection, httpMethod);
            connection.setInstanceFollowRedirects(false);
        }
    }

    /** Công khai trong package để test kiểm chứng cấu hình không follow redirect. */
    public static RestClient noRedirectClient(String baseUrl, ServicesProperties properties) {
        NoRedirectRequestFactory factory = new NoRedirectRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .requestInterceptor(new TokenRelayInterceptor()).build();
    }

    @Bean
    AuthClient authClient(ServicesProperties properties) {
        return new HttpAuthClient(noRedirectClient(properties.identityUrl(), properties));
    }

    @Bean
    AccessClient accessClient(ServicesProperties properties) {
        return new HttpAccessClient(noRedirectClient(properties.identityUrl(), properties));
    }

    @Bean
    UserClient userClient(ServicesProperties properties) {
        return new HttpUserClient(noRedirectClient(properties.identityUrl(), properties));
    }

    @Bean
    IdentityGatewayClient identityGatewayClient(ServicesProperties properties) {
        return new HttpIdentityGatewayClient(noRedirectClient(properties.identityUrl(), properties),
                properties.identityUrl());
    }

    @Bean
    DepartmentClient departmentClient(ServicesProperties properties) {
        return new HttpDepartmentClient(noRedirectClient(properties.identityUrl(), properties));
    }
}
