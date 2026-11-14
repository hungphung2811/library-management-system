package lms.api.support;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import lms.api.config.SecurityConfiguration;
import lms.api.support.client.SupportClient;
import lms.api.support.dto.AuditLogView;
import lms.api.support.dto.PageView;
import lms.api.support.web.AuditLogController;
import lms.api.web.ApiExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

/** Hợp đồng FE của nhật ký kiểm toán: xác thực, ánh xạ DTO và tham số, chuyển nguyên lỗi 4xx. */
@WebMvcTest(AuditLogController.class)
@Import({SecurityConfiguration.class, ApiExceptionHandler.class})
class AuditLogControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean SupportClient support;
    @MockitoBean JwtDecoder jwtDecoder;

    @Test
    void khongTokenBi401() throws Exception {
        mvc.perform(get("/api/audit-logs")).andExpect(status().isUnauthorized());
    }

    @Test
    void locVaPhanTrangChuyenXuongService() throws Exception {
        AuditLogView log = new AuditLogView(UUID.randomUUID(), UUID.randomUUID(), "BookBorrowedEvent", "agg-1",
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:01Z"), "{}");
        when(support.searchAuditLogs(eq("agg-1"), eq("BookBorrowedEvent"), eq(1), eq(2)))
                .thenReturn(new PageView<>(List.of(log), 5, 1, 2));
        mvc.perform(get("/api/audit-logs").param("aggregateId", "agg-1").param("eventType", "BookBorrowedEvent")
                        .param("page", "1").param("size", "2").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.items[0].eventType").value("BookBorrowedEvent"));
    }

    @Test
    void macDinhTrang0Kich20() throws Exception {
        when(support.searchAuditLogs(eq(null), eq(null), eq(0), eq(20))).thenReturn(new PageView<>(List.of(), 0, 0, 20));
        mvc.perform(get("/api/audit-logs").with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void loi4xxChuyenNguyenVaMatKetNoiThanh503() throws Exception {
        String body = "{\"code\":\"INVALID_PAGE_SIZE\",\"message\":\"size quá lớn\",\"details\":[]}";
        when(support.searchAuditLogs(eq(null), eq(null), eq(0), eq(1000))).thenThrow(
                HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad", HttpHeaders.EMPTY,
                        body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        mvc.perform(get("/api/audit-logs").param("size", "1000").with(jwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE_SIZE"));

        when(support.searchAuditLogs(eq(null), eq(null), eq(0), eq(20))).thenThrow(new ResourceAccessException("down"));
        mvc.perform(get("/api/audit-logs").with(jwt())).andExpect(status().isServiceUnavailable());
    }
}
