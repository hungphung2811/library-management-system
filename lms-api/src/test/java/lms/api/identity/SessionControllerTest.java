package lms.api.identity;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import lms.api.config.SecurityConfiguration;
import lms.api.identity.client.AuthClient;
import lms.api.identity.client.UserClient;
import lms.api.identity.dto.AuthDtos.SessionView;
import lms.api.identity.dto.UserDtos.RevokedSessionsView;
import lms.api.identity.dto.UserDtos.UserSessionView;
import lms.api.identity.web.AuthController;
import lms.api.identity.web.SessionController;
import lms.api.identity.web.UserController;
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
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;

/** Hợp đồng /api/auth/sessions và /api/users/{id}/sessions (ADR-0017): cần đăng nhập, no-store, chuyển nguyên mã lỗi. */
@WebMvcTest({SessionController.class, AuthController.class, UserController.class})
@Import({SecurityConfiguration.class, ApiExceptionHandler.class})
class SessionControllerTest {

    static final UUID SID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final UUID UID = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    @Autowired MockMvc mvc;
    @MockitoBean AuthClient auth;
    @MockitoBean UserClient users;
    @MockitoBean JwtDecoder jwtDecoder;

    private static HttpClientErrorException error(HttpStatus status, String code) {
        String body = "{\"code\":\"" + code + "\",\"message\":\"x\"}";
        return HttpClientErrorException.create(status, status.name(), HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private static SessionView session() {
        return new SessionView(SID, "STAFF", null, null, "Chrome trên Windows", "203.0.113.9",
                Instant.parse("2026-11-17T01:00:00Z"), Instant.parse("2026-11-17T02:00:00Z"),
                Instant.parse("2026-11-24T02:00:00Z"), true);
    }

    @Test
    void cacEndpointPhienCuaToiCanDangNhap() throws Exception {
        mvc.perform(get("/api/auth/sessions")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/auth/sessions/" + SID)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/sessions/revocation").contentType(MediaType.APPLICATION_JSON)
                .content("{\"keepCurrent\":true}")).andExpect(status().isUnauthorized());
    }

    @Test
    void lietKePhienTraDanhSachNoStore() throws Exception {
        when(auth.mySessions()).thenReturn(List.of(session()));
        mvc.perform(get("/api/auth/sessions").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$[0].sessionId").value(SID.toString()))
                .andExpect(jsonPath("$[0].channel").value("STAFF"))
                .andExpect(jsonPath("$[0].ipAddress").value("203.0.113.9"))
                .andExpect(jsonPath("$[0].current").value(true));
    }

    @Test
    void thuHoiMotPhienTra204VaChuyenSessionNotFound() throws Exception {
        mvc.perform(delete("/api/auth/sessions/" + SID).with(jwt())).andExpect(status().isNoContent());
        verify(auth).revokeMySession(SID);

        doThrow(error(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND")).when(auth).revokeMySession(any());
        mvc.perform(delete("/api/auth/sessions/" + SID).with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
    }

    @Test
    void thuHoiHangLoatChuyenKeepCurrentVaMaLoi409() throws Exception {
        when(auth.revokeMySessions(true)).thenReturn(new RevokedSessionsView(2));
        mvc.perform(post("/api/auth/sessions/revocation").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keepCurrent\":true}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.revokedSessions").value(2));

        when(auth.revokeMySessions(false)).thenThrow(error(HttpStatus.CONFLICT, "SESSION_NOT_IDENTIFIED"));
        mvc.perform(post("/api/auth/sessions/revocation").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keepCurrent\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_IDENTIFIED"));
    }

    @Test
    void thieuKeepCurrentLa400KhongNgamHieuLaFalse() throws Exception {
        mvc.perform(post("/api/auth/sessions/revocation").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void adminXemVaThuHoiPhienCuaNguoiKhac() throws Exception {
        when(users.listSessions(UID)).thenReturn(List.of(new UserSessionView(SID, "STOREFRONT", "Safari trên iOS",
                "198.51.100.7", Instant.parse("2026-11-17T01:00:00Z"), Instant.parse("2026-11-17T02:00:00Z"),
                Instant.parse("2026-11-24T02:00:00Z"))));
        mvc.perform(get("/api/users/" + UID + "/sessions").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$[0].channel").value("STOREFRONT"))
                .andExpect(jsonPath("$[0].ipAddress").value("198.51.100.7"));
        mvc.perform(get("/api/users/" + UID + "/sessions")).andExpect(status().isUnauthorized());

        mvc.perform(delete("/api/users/" + UID + "/sessions/" + SID).with(jwt())).andExpect(status().isNoContent());
        verify(users).revokeSession(UID, SID);
    }

    @Test
    void chuyenMaLoiAdminPhien() throws Exception {
        when(users.listSessions(UID)).thenThrow(error(HttpStatus.FORBIDDEN, "USER_OPERATION_NOT_ALLOWED"));
        mvc.perform(get("/api/users/" + UID + "/sessions").with(jwt()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_OPERATION_NOT_ALLOWED"));

        doThrow(error(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND")).when(users).revokeSession(UID, SID);
        mvc.perform(delete("/api/users/" + UID + "/sessions/" + SID).with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("SESSION_NOT_FOUND")));
    }

    @Test
    void refreshChuyenNguyenRefreshTokenSuperseded() throws Exception {
        when(auth.refresh("r")).thenThrow(error(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_SUPERSEDED"));
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"r\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_SUPERSEDED"));    }
}
