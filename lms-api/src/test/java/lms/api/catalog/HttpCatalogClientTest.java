package lms.api.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import lms.api.catalog.client.http.HttpCatalogClient;
import lms.api.catalog.dto.AddCopyRequest;
import lms.api.support.TokenRelayInterceptor;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Máy khách gọi đúng đường dẫn nội bộ /internal/**, ánh xạ DTO và chuyển tiếp token của người dùng. */
class HttpCatalogClientTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void goiApiNoiBoChuyenTiepTokenVaAnhXaDto() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://catalog")
                .requestInterceptor(new TokenRelayInterceptor());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpCatalogClient client = new HttpCatalogClient(builder.build());

        Jwt jwt = Jwt.withTokenValue("token-nguoi-dung").header("alg", "none").subject("u1").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        UUID title = UUID.randomUUID();
        server.expect(requestTo("http://catalog/internal/books?available=true&keyword=clean"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer token-nguoi-dung"))
                .andRespond(withSuccess("[{\"id\":\"" + title + "\",\"name\":\"Clean Code\",\"author\":\"Martin\","
                        + "\"isbn\":null,\"coverPrice\":250000.00,\"availableCopies\":3,\"campoMoi\":1}]",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://catalog/internal/book-copies"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.barcode").value("CC-1"))
                .andRespond(withSuccess("{\"id\":\"" + UUID.randomUUID() + "\",\"titleId\":\"" + title
                        + "\",\"barcode\":\"CC-1\",\"status\":\"AVAILABLE\"}", MediaType.APPLICATION_JSON));

        var titles = client.searchTitles("clean", true);
        assertEquals(1, titles.size());
        assertEquals(3, titles.get(0).availableCopies());
        assertEquals("AVAILABLE", client.addCopy(new AddCopyRequest(title, "CC-1")).status());
        server.verify();
    }
}
