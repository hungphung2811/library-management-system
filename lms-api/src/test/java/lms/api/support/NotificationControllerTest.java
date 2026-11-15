package lms.api.support;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import lms.api.config.SecurityConfiguration;
import lms.api.support.client.SupportClient;
import lms.api.support.dto.MarkAllReadView;
import lms.api.support.dto.NotificationView;
import lms.api.support.dto.UnreadCountView;
import lms.api.support.web.NotificationController;
import lms.api.web.ApiExceptionHandler;
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

/** Hợp đồng FE của thông báo: đánh dấu đã đọc, đọc tất cả, đếm chưa đọc; chuyển nguyên lỗi 404. */
@WebMvcTest(NotificationController.class)
@Import({SecurityConfiguration.class, ApiExceptionHandler.class})
class NotificationControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean SupportClient support;
    @MockitoBean JwtDecoder jwtDecoder;

    @Test
    void khongTokenBi401() throws Exception {
        mvc.perform(post("/api/notifications/read-all")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/notifications/" + UUID.randomUUID() + "/read")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/notifications/me/unread-count")).andExpect(status().isUnauthorized());
        verifyNoInteractions(support);
    }

    @Test
    void demChuaDoc() throws Exception {
        when(support.myUnreadNotificationCount()).thenReturn(new UnreadCountView(4));
        mvc.perform(get("/api/notifications/me/unread-count").with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unread").value(4));
    }

    @Test
    void danhDauMotThongBaoDaDoc() throws Exception {
        UUID id = UUID.randomUUID();
        Instant at = Instant.parse("2026-01-01T00:00:00Z");
        when(support.markNotificationRead(id)).thenReturn(new NotificationView(id, "T", "m", at, at));
        mvc.perform(post("/api/notifications/" + id + "/read").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.readAt").exists());
        verify(support).markNotificationRead(id);
    }

    @Test
    void docTatCa() throws Exception {
        when(support.markAllNotificationsRead()).thenReturn(new MarkAllReadView(3));
        mvc.perform(post("/api/notifications/read-all").with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.updated").value(3));
    }

    @Test
    void loi404ChuyenNguyen() throws Exception {
        UUID id = UUID.randomUUID();
        String body = "{\"code\":\"NOTIFICATION_NOT_FOUND\",\"message\":\"Không tìm thấy thông báo\",\"details\":[]}";
        when(support.markNotificationRead(id)).thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "NF",
                HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        mvc.perform(post("/api/notifications/" + id + "/read").with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
    }
}
