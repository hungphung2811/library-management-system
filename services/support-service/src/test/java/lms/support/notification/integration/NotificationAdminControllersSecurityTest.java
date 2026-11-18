package lms.support.notification.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import lms.support.config.SecurityConfiguration;
import lms.support.notification.application.DeliveryUseCases;
import lms.support.notification.application.DeliveryUseCases.DeliveryPageView;
import lms.support.notification.application.DeliveryUseCases.DeliveryView;
import lms.support.notification.application.NotificationSettingsUseCases;
import lms.support.notification.application.NotificationSettingsUseCases.ChannelSettingView;
import lms.support.notification.application.NotificationSettingsUseCases.PreferenceView;
import lms.support.notification.application.NotificationTemplateUseCases;
import lms.support.notification.application.NotificationTemplateUseCases.TemplateView;
import lms.support.notification.domain.DeliveryNotFoundException;
import lms.support.notification.domain.DeliveryNotRetryableException;
import lms.support.notification.domain.NotificationCategory;
import lms.support.notification.domain.NotificationType;
import lms.support.notification.domain.PreferenceMandatoryException;
import lms.support.notification.domain.TemplateParamNotAllowedException;
import lms.support.notification.infrastructure.web.NotificationAdminExceptionHandler;
import lms.support.notification.infrastructure.web.NotificationDeliveryController;
import lms.support.notification.infrastructure.web.NotificationPreferenceController;
import lms.support.notification.infrastructure.web.NotificationSettingsController;
import lms.support.notification.infrastructure.web.NotificationTemplateController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Phân quyền động (ADR-0003) của API thông báo ở support-service: endpoint quản trị cần khóa Controller/method (admin
 * bỏ qua), endpoint "của tôi" chỉ cần đăng nhập. Bật lại kiểm khóa và dùng PermissionKeyRegistrar giả.
 */
@WebMvcTest({NotificationDeliveryController.class, NotificationSettingsController.class,
        NotificationTemplateController.class, NotificationPreferenceController.class})
@Import({SecurityConfiguration.class, NotificationAdminExceptionHandler.class, PermissionAutoConfiguration.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class NotificationAdminControllersSecurityTest {

    private static final String TENANT = "00000000-0000-0000-0000-0000000000a1";
    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired MockMvc mvc;

    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;
    @MockitoBean DeliveryUseCases deliveries;
    @MockitoBean NotificationSettingsUseCases settings;
    @MockitoBean NotificationTemplateUseCases templates;
    @MockitoBean JwtDecoder jwtDecoder;

    private static RequestPostProcessor withKey(String key) {
        return jwt().jwt(j -> j.subject(USER.toString()).claim("tenantId", TENANT))
                .authorities(new SimpleGrantedAuthority(key));
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject(USER.toString()).claim("tenantId", TENANT).claim("admin", true));
    }

    private static RequestPostProcessor plainUser() {
        return jwt().jwt(j -> j.subject(USER.toString()).claim("tenantId", TENANT))
                .authorities(new SimpleGrantedAuthority("BOOK_VIEW"));
    }

    // ---- lịch sử gửi / gửi lại ----

    @Test
    void lichSuGuiKhongTokenBi401ThieuKhoaBi403DuKhoaHoacAdminDuoc200() throws Exception {
        when(deliveries.search(any())).thenReturn(new DeliveryPageView(List.of(), 0, 0, 20));
        mvc.perform(get("/internal/notification-deliveries")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/notification-deliveries").with(plainUser())).andExpect(status().isForbidden());
        mvc.perform(get("/internal/notification-deliveries").with(withKey("NotificationDeliveryController/retry")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/internal/notification-deliveries").with(withKey("NotificationDeliveryController/search")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/internal/notification-deliveries").with(admin())).andExpect(status().isOk());
    }

    @Test
    void lichSuGuiBindThamSoLocVaTraDeliveryDaChe() throws Exception {
        when(deliveries.search(any())).thenReturn(new DeliveryPageView(List.of(), 0, 1, 5));
        mvc.perform(get("/internal/notification-deliveries").param("status", "DEAD").param("channel", "EMAIL")
                        .param("type", "ORDER_PAID").param("page", "1").param("size", "5")
                        .param("from", "2026-11-01T00:00:00Z").with(admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(1));
        verify(deliveries).search(org.mockito.ArgumentMatchers.argThat(c -> c.status() != null
                && c.status().name().equals("DEAD") && c.page() == 1 && c.size() == 5
                && "ORDER_PAID".equals(c.type()) && c.from().equals(Instant.parse("2026-11-01T00:00:00Z"))));
    }

    @Test
    void trangThaiLocSaiBi400() throws Exception {
        mvc.perform(get("/internal/notification-deliveries").param("status", "KHONG_CO").with(admin()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(deliveries);
    }

    @Test
    void guiLaiCanKhoaRetryVaLayNguoiBamTuToken() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(post("/internal/notification-deliveries/" + id + "/retry").with(plainUser()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/internal/notification-deliveries/" + id + "/retry")
                        .with(withKey("NotificationDeliveryController/search"))).andExpect(status().isForbidden());
        verifyNoInteractions(deliveries);

        when(deliveries.retry(eq(id), eq(USER))).thenReturn(new DeliveryView(id, UUID.randomUUID(), "E", "ORDER_PAID",
                "ORDER", "EMAIL", "PENDING", null, null, "n***@example.com", 6, Instant.now(), null, null, null, null,
                null, USER, Instant.now(), Instant.now()));
        mvc.perform(post("/internal/notification-deliveries/" + id + "/retry")
                        .with(withKey("NotificationDeliveryController/retry")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.recipientAddressMasked").value("n***@example.com"));
    }

    @Test
    void guiLaiLoiNghiepVu404Va409() throws Exception {
        UUID missing = UUID.randomUUID();
        UUID sent = UUID.randomUUID();
        when(deliveries.retry(eq(missing), any())).thenThrow(new DeliveryNotFoundException());
        when(deliveries.retry(eq(sent), any())).thenThrow(new DeliveryNotRetryableException());
        mvc.perform(post("/internal/notification-deliveries/" + missing + "/retry").with(admin()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DELIVERY_NOT_FOUND"));
        mvc.perform(post("/internal/notification-deliveries/" + sent + "/retry").with(admin()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DELIVERY_NOT_RETRYABLE"));
        mvc.perform(post("/internal/notification-deliveries/abc/retry").with(admin())).andExpect(status().isBadRequest());
    }

    @Test
    void khongCoTenantBi403TenantRequiredKeCaKhiDuKhoa() throws Exception {
        mvc.perform(get("/internal/notification-deliveries").with(jwt().authorities(
                        new SimpleGrantedAuthority("NotificationDeliveryController/search"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
    }

    // ---- cấu hình kênh ----

    @Test
    void cauHinhKenhCanKhoaRiengChoDocVaGhi() throws Exception {
        when(settings.channels()).thenReturn(List.of(new ChannelSettingView("ORDER_PAID", "ORDER", "EMAIL", true)));
        mvc.perform(get("/internal/notification-settings/channels").with(plainUser())).andExpect(status().isForbidden());
        mvc.perform(get("/internal/notification-settings/channels")
                        .with(withKey("NotificationSettingsController/getChannels")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].enabled").value(true));
        String body = "{\"items\":[{\"type\":\"ORDER_PAID\",\"channel\":\"EMAIL\",\"enabled\":false}]}";
        mvc.perform(put("/internal/notification-settings/channels").contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(withKey("NotificationSettingsController/getChannels")))
                .andExpect(status().isForbidden());
        mvc.perform(put("/internal/notification-settings/channels").contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(withKey("NotificationSettingsController/updateChannels")))
                .andExpect(status().isOk());
        verify(settings).updateChannels(any(), eq(USER));
    }

    @Test
    void cauHinhKenhBodySaiBi400() throws Exception {
        for (String body : new String[] {"{}", "{\"items\":null}",
                "{\"items\":[{\"type\":\"KHONG_CO\",\"channel\":\"EMAIL\",\"enabled\":true}]}",
                "{\"items\":[{\"type\":\"ORDER_PAID\",\"channel\":\"SMS\",\"enabled\":true}]}",
                "{\"items\":[{\"type\":\"ORDER_PAID\",\"channel\":\"EMAIL\"}]}", "khong-phai-json"}) {
            mvc.perform(put("/internal/notification-settings/channels").contentType(MediaType.APPLICATION_JSON)
                            .content(body).with(admin()))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        verifyNoInteractions(settings);
    }

    // ---- mẫu ----

    @Test
    void mauCanBonKhoaRiengLietKeXemLuuXemTruoc() throws Exception {
        TemplateView v = new TemplateView("ORDER_PAID", "ORDER", "EMAIL", "DEFAULT", 1, "S", "B", List.of("order_no"));
        when(templates.list()).thenReturn(List.of(v));
        when(templates.get(NotificationType.ORDER_PAID, lms.support.notification.domain.NotificationChannel.EMAIL))
                .thenReturn(java.util.Optional.of(v));
        mvc.perform(get("/internal/notification-templates").with(plainUser())).andExpect(status().isForbidden());
        mvc.perform(get("/internal/notification-templates").with(withKey("NotificationTemplateController/list")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].type").value("ORDER_PAID"));
        mvc.perform(get("/internal/notification-templates/ORDER_PAID/EMAIL")
                        .with(withKey("NotificationTemplateController/list"))).andExpect(status().isForbidden());
        mvc.perform(get("/internal/notification-templates/ORDER_PAID/EMAIL")
                        .with(withKey("NotificationTemplateController/get"))).andExpect(status().isOk());
        mvc.perform(get("/internal/notification-templates/ORDER_SHIPPING/EMAIL").with(admin()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/internal/notification-templates/KHONG_CO/EMAIL").with(admin()))
                .andExpect(status().isBadRequest());

        String body = "{\"subject\":\"Đã thu {{grand_total}}\",\"body\":\"Cảm ơn\"}";
        mvc.perform(put("/internal/notification-templates/ORDER_PAID/EMAIL").contentType(MediaType.APPLICATION_JSON)
                        .content(body).with(withKey("NotificationTemplateController/get")))
                .andExpect(status().isForbidden());
        mvc.perform(put("/internal/notification-templates/ORDER_PAID/EMAIL").contentType(MediaType.APPLICATION_JSON)
                        .content(body).with(withKey("NotificationTemplateController/save")))
                .andExpect(status().isOk());
        mvc.perform(post("/internal/notification-templates/ORDER_PAID/EMAIL/preview")
                        .contentType(MediaType.APPLICATION_JSON).content("{}")
                        .with(withKey("NotificationTemplateController/save"))).andExpect(status().isForbidden());
        mvc.perform(post("/internal/notification-templates/ORDER_PAID/EMAIL/preview")
                        .contentType(MediaType.APPLICATION_JSON).content("{}")
                        .with(withKey("NotificationTemplateController/preview"))).andExpect(status().isOk());
    }

    @Test
    void luuMauThamSoLaBi400VaMauTrongBi400() throws Exception {
        when(templates.save(any(), any(), any(), any(), any()))
                .thenThrow(new TemplateParamNotAllowedException(java.util.Set.of("password")));
        mvc.perform(put("/internal/notification-templates/ORDER_PAID/EMAIL").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"x {{password}}\",\"body\":\"y\"}").with(admin()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TEMPLATE_PARAM_NOT_ALLOWED"));
        mvc.perform(put("/internal/notification-templates/ORDER_PAID/EMAIL").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\" \",\"body\":\"y\"}").with(admin()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---- của tôi: chỉ cần đăng nhập ----

    @Test
    void tuyChonVaLichSuCuaToiChiCanDangNhapVaLayUserTuToken() throws Exception {
        when(settings.preferences(USER)).thenReturn(List.of(new PreferenceView("FINE", "EMAIL", true, false)));
        mvc.perform(get("/internal/notifications/me/preferences")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/notifications/me/preferences").with(plainUser()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].category").value("FINE"));
        mvc.perform(get("/internal/notifications/me/deliveries").with(jwt().jwt(j -> j.subject(USER.toString()))))
                .andExpect(status().isOk());
        verify(deliveries).mine(USER);
    }

    @Test
    void tatNhomBatBuocBi400PreferenceMandatory() throws Exception {
        when(settings.updatePreferences(eq(USER), any())).thenThrow(new PreferenceMandatoryException(NotificationCategory.ACCOUNT));
        mvc.perform(put("/internal/notifications/me/preferences").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"category\":\"ACCOUNT\",\"channel\":\"EMAIL\",\"enabled\":false}]}")
                        .with(plainUser()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PREFERENCE_MANDATORY"));
    }

    @Test
    void capNhatTuyChonHopLeGoiUseCaseVoiUserCuaToken() throws Exception {
        when(settings.updatePreferences(eq(USER), any())).thenReturn(List.of());
        mvc.perform(put("/internal/notifications/me/preferences").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"category\":\"FINE\",\"channel\":\"EMAIL\",\"enabled\":false}]}")
                        .with(plainUser()))
                .andExpect(status().isOk());
        verify(settings).updatePreferences(eq(USER), any());
        mvc.perform(put("/internal/notifications/me/preferences").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"category\":\"NOPE\",\"channel\":\"EMAIL\",\"enabled\":false}]}")
                        .with(plainUser()))
                .andExpect(status().isBadRequest());
    }
}
