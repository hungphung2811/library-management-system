package lms.integration.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestSizeLimitFilterTest {

    private final RequestSizeLimitFilter filter = new RequestSizeLimitFilter(new JsonErrorWriter(new ObjectMapper()
            .findAndRegisterModules()));

    private MockHttpServletResponse run(String uri, int contentLength) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setContent(new byte[contentLength]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void tokenEndpointChiNhan4KB() throws Exception {
        assertThat(run(SecurityConfiguration.TOKEN_PATH, 4 * 1024).getStatus()).isEqualTo(200);
        MockHttpServletResponse over = run(SecurityConfiguration.TOKEN_PATH, 4 * 1024 + 1);
        assertThat(over.getStatus()).isEqualTo(413);
        assertThat(over.getContentAsString()).contains("PAYLOAD_TOO_LARGE");
        assertThat(over.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void duongDanKhacNhan64KB() throws Exception {
        assertThat(run("/api/integration/v1/books", 64 * 1024).getStatus()).isEqualTo(200);
        assertThat(run("/api/integration/v1/books", 64 * 1024 + 1).getStatus()).isEqualTo(413);
    }
}
