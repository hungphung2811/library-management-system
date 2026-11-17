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
import lms.api.support.ClientInfoResolver;
import lms.api.support.TokenRelayInterceptor;
import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Client của identity dùng RestClient KHÔNG tự follow redirect: luồng đăng nhập ngoài cần nhận nguyên 302 (Location,
 * Set-Cookie) của service để chuyển cho trình duyệt. Các lời gọi còn lại không có redirect nên dùng chung cấu hình này.
 */
@Configuration
public class IdentityClientConfiguration {

    /**
     * Công khai trong package để test kiểm chứng. Dùng java.net.http (JdkClientHttpRequestFactory) thay vì
     * HttpURLConnection: HttpURLConnection không đọc được thân của 401 trên POST (không kèm WWW-Authenticate) nên
     * REFRESH_TOKEN_SUPERSEDED/UNAUTHORIZED của identity bị đổi thành DOWNSTREAM_REJECTED. HTTP/1.1 để khỏi gửi
     * header nâng cấp h2c.
     */
    public static RestClient noRedirectClient(String baseUrl, ServicesProperties properties) {
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.readTimeout());
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .requestInterceptor(new TokenRelayInterceptor()).build();
    }

    @Bean
    AuthClient authClient(ServicesProperties properties, ClientInfoResolver clientInfo) {
        return new HttpAuthClient(noRedirectClient(properties.identityUrl(), properties), clientInfo::current);
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
