package lms.security.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RemotePermissionKeyRegistrarTest {

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://identity");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final RemotePermissionKeyRegistrar registrar =
            new RemotePermissionKeyRegistrar(builder.build(), "secret", Runnable::run, Clock.systemUTC(), Duration.ofSeconds(30));

    private final EndpointDescriptor descriptor =
            new EndpointDescriptor("circulation-service", "BorrowingController/borrow", "AUTO", "POST", "/api/borrowings", "mô tả");

    @Test
    void guiDescriptorToiApiNoiBoKemKhoaChiaSe() {
        server.expect(requestTo("http://identity/internal/permissions/registrations"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Api-Key", "secret"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.key").value("BorrowingController/borrow"))
                .andExpect(jsonPath("$.service").value("circulation-service"))
                .andExpect(jsonPath("$.source").value("AUTO"))
                .andExpect(jsonPath("$.pathPattern").value("/api/borrowings"))
                .andRespond(withNoContent());

        registrar.ensureRegistered(descriptor);

        server.verify();
        assertThat(registrar.isRegistered("BorrowingController/borrow")).isTrue();
    }

    @Test
    void identityLoiThiChuaDanhDauDaDangKyDeThuLaiSau() {
        server.expect(requestTo("http://identity/internal/permissions/registrations")).andRespond(withServerError());

        registrar.ensureRegistered(descriptor);

        assertThat(registrar.isRegistered("BorrowingController/borrow")).isFalse();
    }
}
