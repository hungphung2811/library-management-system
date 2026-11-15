package lms.support.notification.integration;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import lms.support.config.SecurityConfiguration;
import lms.support.notification.application.CountUnreadNotificationsUseCase;
import lms.support.notification.application.ListMyNotificationsUseCase;
import lms.support.notification.application.MarkAllNotificationsReadUseCase;
import lms.support.notification.application.MarkNotificationReadUseCase;
import lms.support.notification.domain.NotificationNotFoundException;
import lms.support.notification.application.NotificationResponse;
import lms.support.notification.infrastructure.web.NotificationController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** NotificationController/mine là @AuthenticatedOnly: chỉ cần đăng nhập, userId lấy từ sub của JWT. */
@WebMvcTest(NotificationController.class)
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class NotificationControllerSecurityTest {

    private static final String TENANT = "00000000-0000-0000-0000-0000000000a1";
    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired MockMvc mvc;

    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;
    @MockitoBean ListMyNotificationsUseCase listMine;
    @MockitoBean MarkNotificationReadUseCase markRead;
    @MockitoBean MarkAllNotificationsReadUseCase markAllRead;
    @MockitoBean CountUnreadNotificationsUseCase countUnread;
    @MockitoBean JwtDecoder jwtDecoder;

    @Test
    void khongTokenBi401() throws Exception {
        mvc.perform(get("/internal/notifications/me")).andExpect(status().isUnauthorized());
        verifyNoInteractions(listMine);
    }

    @Test
    void chiDangNhapLaDuKhongCanKhoaQuyen() throws Exception {
        when(listMine.execute(USER)).thenReturn(List.of());

        mvc.perform(get("/internal/notifications/me")
                        .with(jwt().jwt(j -> j.subject(USER.toString()).claim("tenantId", TENANT))
                                .authorities(new SimpleGrantedAuthority("BOOK_VIEW"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void tokenKhongCoAuthoritiesVanDuocXemThongBaoCuaMinh() throws Exception {
        when(listMine.execute(USER)).thenReturn(List.of());

        mvc.perform(get("/internal/notifications/me")
                        .with(jwt().jwt(j -> j.subject(USER.toString()).claim("tenantId", TENANT))))
                .andExpect(status().isOk());
    }

    @Test
    void khongCoTenantVanDuocVi_AuthenticatedOnly() throws Exception {
        when(listMine.execute(USER)).thenReturn(List.of());

        mvc.perform(get("/internal/notifications/me").with(jwt().jwt(j -> j.subject(USER.toString()))))
                .andExpect(status().isOk());
    }

    @Test
    void userIdLayTuSubCuaToken() throws Exception {
        UUID id = UUID.fromString("22222222-2222-2222-2222-222222222222");
        Instant created = Instant.parse("2026-01-02T03:04:05Z");
        when(listMine.execute(USER))
                .thenReturn(List.of(new NotificationResponse(id, "FINE_CREATED", "Bạn bị phạt", created, null)));

        mvc.perform(get("/internal/notifications/me")
                        .with(jwt().jwt(j -> j.subject(USER.toString()).claim("tenantId", TENANT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].type").value("FINE_CREATED"))
                .andExpect(jsonPath("$[0].message").value("Bạn bị phạt"))
                .andExpect(jsonPath("$[0].createdAt").exists())
                .andExpect(jsonPath("$[0].readAt").doesNotExist());
        verify(listMine).execute(USER);
    }

    @Test
    void adminCungChiXemThongBaoCuaMinh() throws Exception {
        when(listMine.execute(USER)).thenReturn(List.of());

        mvc.perform(get("/internal/notifications/me")
                        .with(jwt().jwt(j -> j.subject(USER.toString()).claim("admin", true).claim("tenantId", TENANT))))
                .andExpect(status().isOk());
        verify(listMine).execute(USER);
    }

    @Test
    void subKhongPhaiUuidKhongDuocGay5xx() throws Exception {
        mvc.perform(get("/internal/notifications/me")
                        .with(jwt().jwt(j -> j.subject("khong-phai-uuid").claim("tenantId", TENANT))))
                .andExpect(status().isUnauthorized())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("khong-phai-uuid"))));
        verifyNoInteractions(listMine);
    }

    @Test
    void danhDauDaDocLayUserTuTokenVaTraThongBao() throws Exception {
        UUID id = UUID.fromString("22222222-2222-2222-2222-222222222222");
        Instant at = Instant.parse("2026-01-02T03:04:05Z");
        when(markRead.execute(id, USER)).thenReturn(new NotificationResponse(id, "T", "m", at, at));

        mvc.perform(post("/internal/notifications/" + id + "/read")
                        .with(jwt().jwt(j -> j.subject(USER.toString()).claim("tenantId", TENANT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.readAt").exists());
        verify(markRead).execute(id, USER);
    }

    @Test
    void danhDauThongBaoKhongCoCuaMinhLa404() throws Exception {
        UUID id = UUID.randomUUID();
        when(markRead.execute(id, USER)).thenThrow(new NotificationNotFoundException());

        mvc.perform(post("/internal/notifications/" + id + "/read")
                        .with(jwt().jwt(j -> j.subject(USER.toString()).claim("tenantId", TENANT))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
    }

    @Test
    void idKhongPhaiUuidLa400() throws Exception {
        mvc.perform(post("/internal/notifications/abc/read")
                        .with(jwt().jwt(j -> j.subject(USER.toString()).claim("tenantId", TENANT))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(markRead);
    }

    @Test
    void docTatCaVaDemChuaDoc() throws Exception {
        when(markAllRead.execute(USER)).thenReturn(3);
        when(countUnread.execute(USER)).thenReturn(5L);

        mvc.perform(post("/internal/notifications/read-all")
                        .with(jwt().jwt(j -> j.subject(USER.toString()).claim("tenantId", TENANT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(3));
        mvc.perform(get("/internal/notifications/me/unread-count")
                        .with(jwt().jwt(j -> j.subject(USER.toString()).claim("tenantId", TENANT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread").value(5));
    }

    @Test
    void cacEndpointMoiCanDangNhap() throws Exception {
        mvc.perform(post("/internal/notifications/read-all")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/notifications/" + UUID.randomUUID() + "/read")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/notifications/me/unread-count")).andExpect(status().isUnauthorized());
        verifyNoInteractions(markRead, markAllRead, countUnread);
    }

    @Test
    void phuongThucKhacGetKhongDuocPhep() throws Exception {
        mvc.perform(post("/internal/notifications/me")
                        .with(jwt().jwt(j -> j.subject(USER.toString()).claim("tenantId", TENANT))))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void duongDanNgoaiInternalBiDenyAll() throws Exception {
        mvc.perform(get("/api/notifications/me").with(jwt().jwt(j -> j.subject(USER.toString()))))
                .andExpect(status().isForbidden());
    }
}
