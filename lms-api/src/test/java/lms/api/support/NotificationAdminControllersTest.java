package lms.api.support;

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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.api.config.SecurityConfiguration;
import lms.api.support.client.NotificationAdminClient;
import lms.api.support.dto.NotificationAdminDtos.ChannelSettingView;
import lms.api.support.dto.NotificationAdminDtos.DeliveryPageView;
import lms.api.support.dto.NotificationAdminDtos.DeliveryView;
import lms.api.support.dto.NotificationAdminDtos.PreferenceView;
import lms.api.support.dto.NotificationAdminDtos.TemplatePreviewView;
import lms.api.support.dto.NotificationAdminDtos.TemplateView;
import lms.api.support.web.NotificationDeliveryController;
import lms.api.support.web.NotificationPreferenceController;
import lms.api.support.web.NotificationSettingsController;
import lms.api.support.web.NotificationTemplateController;
import lms.api.web.ApiExceptionHandler;
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

/** Hợp đồng FE của thông báo theo sự kiện (ADR-0030): tùy chọn nhận, lịch sử gửi, cấu hình kênh, mẫu; lỗi chuyển nguyên. */
@WebMvcTest({NotificationPreferenceController.class, NotificationDeliveryController.class,
        NotificationSettingsController.class, NotificationTemplateController.class})
@Import({SecurityConfiguration.class, ApiExceptionHandler.class})
class NotificationAdminControllersTest {

    @Autowired MockMvc mvc;
    @MockitoBean NotificationAdminClient notifications;
    @MockitoBean JwtDecoder jwtDecoder;

    @Test
    void khongTokenBi401MoiEndpoint() throws Exception {
        mvc.perform(get("/api/notifications/me/preferences")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/notifications/me/preferences").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/notifications/me/deliveries")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/notification-deliveries")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/notification-deliveries/" + UUID.randomUUID() + "/retry")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/notification-settings/channels")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/notification-templates")).andExpect(status().isUnauthorized());
        verifyNoInteractions(notifications);
    }

    @Test
    void tuyChonCuaToiDocVaCapNhat() throws Exception {
        when(notifications.myPreferences()).thenReturn(List.of(new PreferenceView("FINE", "EMAIL", true, false)));
        mvc.perform(get("/api/notifications/me/preferences").with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].category").value("FINE"))
                .andExpect(jsonPath("$[0].mandatory").value(false));
        when(notifications.updateMyPreferences(any())).thenReturn(List.of());
        mvc.perform(put("/api/notifications/me/preferences").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"category\":\"FINE\",\"channel\":\"EMAIL\",\"enabled\":false}]}"))
                .andExpect(status().isOk());
        verify(notifications).updateMyPreferences(any());
    }

    @Test
    void tuyChonBodySaiBi400VaKhongGoiService() throws Exception {
        for (String body : new String[] {"{}", "{\"items\":[{\"category\":\"FINE\",\"channel\":\"EMAIL\"}]}",
                "{\"items\":[{\"category\":\"\",\"channel\":\"EMAIL\",\"enabled\":true}]}"}) {
            mvc.perform(put("/api/notifications/me/preferences").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(notifications);
    }

    @Test
    void tatNhomBatBuocLoi400CuaServiceDuocChuyenNguyen() throws Exception {
        byte[] body = "{\"code\":\"PREFERENCE_MANDATORY\",\"message\":\"Nhóm bắt buộc\"}".getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        when(notifications.updateMyPreferences(any())).thenThrow(
                HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request", headers, body, StandardCharsets.UTF_8));
        mvc.perform(put("/api/notifications/me/preferences").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"category\":\"ACCOUNT\",\"channel\":\"EMAIL\",\"enabled\":false}]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PREFERENCE_MANDATORY"));
    }

    @Test
    void lichSuGuiBindThamSoLocVaMacDinhTrang() throws Exception {
        when(notifications.searchDeliveries(any(), any(), any(), any(), any(), eq(0), eq(20)))
                .thenReturn(new DeliveryPageView(List.of(), 0, 0, 20));
        mvc.perform(get("/api/notification-deliveries").with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        verify(notifications).searchDeliveries(null, null, null, null, null, 0, 20);

        when(notifications.searchDeliveries(eq("DEAD"), eq("EMAIL"), eq("ORDER_PAID"), eq("2026-11-01T00:00:00Z"),
                eq("2026-11-30T00:00:00Z"), eq(2), eq(5))).thenReturn(new DeliveryPageView(List.of(), 0, 2, 5));
        mvc.perform(get("/api/notification-deliveries").param("status", "DEAD").param("channel", "EMAIL")
                        .param("type", "ORDER_PAID").param("from", "2026-11-01T00:00:00Z")
                        .param("to", "2026-11-30T00:00:00Z").param("page", "2").param("size", "5").with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(2));
    }

    @Test
    void guiLaiChuyenIdVaTraDeliveryDaChe() throws Exception {
        UUID id = UUID.randomUUID();
        when(notifications.retryDelivery(id)).thenReturn(new DeliveryView(id, UUID.randomUUID(), "E", "ORDER_PAID",
                "ORDER", "EMAIL", "PENDING", null, null, "n***@example.com", 6, Instant.now(), null, null, null, null,
                null, null, null, Instant.now()));
        mvc.perform(post("/api/notification-deliveries/" + id + "/retry").with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.recipientAddressMasked").value("n***@example.com"));
        mvc.perform(post("/api/notification-deliveries/abc/retry").with(jwt())).andExpect(status().isBadRequest());
    }

    @Test
    void cauHinhKenhDocVaGhi() throws Exception {
        when(notifications.channelSettings()).thenReturn(List.of(new ChannelSettingView("ORDER_PAID", "ORDER", "EMAIL", true)));
        mvc.perform(get("/api/notification-settings/channels").with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].enabled").value(true));
        when(notifications.updateChannelSettings(any())).thenReturn(List.of());
        mvc.perform(put("/api/notification-settings/channels").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"type\":\"ORDER_PAID\",\"channel\":\"EMAIL\",\"enabled\":false}]}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/notification-settings/channels").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"type\":\"ORDER_PAID\",\"channel\":\"EMAIL\"}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mauListGetSavePreview() throws Exception {
        TemplateView v = new TemplateView("ORDER_PAID", "ORDER", "EMAIL", "DEFAULT", 1, "S", "B", List.of("order_no"));
        when(notifications.templates()).thenReturn(List.of(v));
        when(notifications.template("ORDER_PAID", "EMAIL")).thenReturn(v);
        when(notifications.saveTemplate(eq("ORDER_PAID"), eq("EMAIL"), any())).thenReturn(v);
        when(notifications.previewTemplate(eq("ORDER_PAID"), eq("EMAIL"), any())).thenReturn(new TemplatePreviewView("S", "B"));

        mvc.perform(get("/api/notification-templates").with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].allowedParams[0]").value("order_no"));
        mvc.perform(get("/api/notification-templates/ORDER_PAID/EMAIL").with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(put("/api/notification-templates/ORDER_PAID/EMAIL").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"Đã thu {{grand_total}}\",\"body\":\"Cảm ơn\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/notification-templates/ORDER_PAID/EMAIL/preview").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.subject").value("S"));
    }

    @Test
    void luuMauTrongHoacQuaDaiBi400VaKhongGoiService() throws Exception {
        for (String body : new String[] {"{\"subject\":\" \",\"body\":\"x\"}", "{\"subject\":\"x\"}", "{}",
                "{\"subject\":\"" + "a".repeat(201) + "\",\"body\":\"x\"}",
                "{\"subject\":\"x\",\"body\":\"" + "a".repeat(5001) + "\"}"}) {
            mvc.perform(put("/api/notification-templates/ORDER_PAID/EMAIL").with(jwt())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(notifications);
    }

    @Test
    void loi403VaTemplateParamNotAllowedCuaServiceDuocChuyenNguyen() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        when(notifications.saveTemplate(any(), any(), any())).thenThrow(HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "Bad Request", headers,
                "{\"code\":\"TEMPLATE_PARAM_NOT_ALLOWED\",\"message\":\"x\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));
        mvc.perform(put("/api/notification-templates/ORDER_PAID/EMAIL").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"{{password}}\",\"body\":\"x\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TEMPLATE_PARAM_NOT_ALLOWED"));

        when(notifications.channelSettings()).thenThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden",
                headers, "{\"code\":\"FORBIDDEN\",\"message\":\"x\"}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        mvc.perform(get("/api/notification-settings/channels").with(jwt())).andExpect(status().isForbidden());
    }
}
