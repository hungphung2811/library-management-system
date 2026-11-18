package lms.identity.user.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import lms.identity.config.SecurityConfiguration;
import lms.identity.user.application.LookupNotificationRecipientsUseCase;
import lms.identity.user.application.LookupNotificationRecipientsUseCase.Recipient;
import lms.identity.user.application.LookupNotificationRecipientsUseCase.Result;
import lms.identity.user.domain.InvalidUserException;
import lms.identity.user.infrastructure.web.NotificationRecipientController;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * POST /internal/notification-recipients/lookup (ADR-0030 D5): công khai ở lớp xác thực, chỉ cho phép khi đúng bí mật
 * riêng X-Notification-Client-Secret; bí mật rỗng thì từ chối mọi lời gọi; không thay được bằng INTERNAL_API_KEY hay JWT;
 * chỉ trả dữ liệu tối thiểu.
 */
@WebMvcTest(NotificationRecipientController.class)
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class})
@TestPropertySource(properties = {"lms.security.permission.enabled=true",
        "lms.notification.client-secret=notify-secret-123", "app.internal-api-key=internal-key-999"})
class NotificationRecipientControllerSecurityTest {

    static final String PATH = "/internal/notification-recipients/lookup";
    static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID USER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final String BODY = "{\"tenantId\":\"" + TENANT + "\",\"userIds\":[\"" + USER + "\"]}";

    @Autowired MockMvc mvc;

    @MockitoBean LookupNotificationRecipientsUseCase lookup;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    @Test
    void dungBiMatKhongCanJwtTraDungHopDongToiThieu() throws Exception {
        when(lookup.execute(any(), any())).thenReturn(new Result("Hiệu sách A",
                List.of(new Recipient(USER, "doc.gia@example.com", "Độc Giả", true))));

        mvc.perform(post(PATH).header("X-Notification-Client-Secret", "notify-secret-123")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantDisplayName").value("Hiệu sách A"))
                .andExpect(jsonPath("$.recipients.length()").value(1))
                .andExpect(jsonPath("$.recipients[0].userId").value(USER.toString()))
                .andExpect(jsonPath("$.recipients[0].email").value("doc.gia@example.com"))
                .andExpect(jsonPath("$.recipients[0].displayName").value("Độc Giả"))
                .andExpect(jsonPath("$.recipients[0].active").value(true))
                // Hợp đồng tối thiểu: đúng 4 trường người nhận + tên tenant, không có điện thoại, số thẻ, ngày sinh...
                .andExpect(jsonPath("$.recipients[0].length()").value(4))
                .andExpect(jsonPath("$.length()").value(2));
        verify(lookup).execute(TENANT, List.of(USER));
    }

    @Test
    void thieuSaiHoacRongBiMat401VaKhongTraCuu() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(post(PATH).header("X-Notification-Client-Secret", "sai")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).header("X-Notification-Client-Secret", "NOTIFY-SECRET-123")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).header("X-Notification-Client-Secret", "")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verify(lookup, never()).execute(any(), any());
    }

    @Test
    void thongBaoLoiKhongLoDuLieuNguoiNhan() throws Exception {
        mvc.perform(post(PATH).header("X-Notification-Client-Secret", "sai")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(USER.toString()))));
    }

    @Test
    void khongDungInternalApiKeyHoacJwtThayBiMat() throws Exception {
        mvc.perform(post(PATH).header("X-Internal-Api-Key", "internal-key-999")
                        .header("X-Notification-Client-Secret", "internal-key-999")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).with(jwt().jwt(j -> j.claim("admin", true)).jwt(j -> j.claim("platformAdmin", true)))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verify(lookup, never()).execute(any(), any());
    }

    @Test
    void bodySaiTra400VaKhongTraCuu() throws Exception {
        for (String body : new String[] {"{}", "{\"userIds\":[]}", "{\"tenantId\":\"khong-uuid\"}",
                "{\"tenantId\":\"" + TENANT + "\",\"userIds\":[\"khong-uuid\"]}", "khong-phai-json"}) {
            mvc.perform(post(PATH).header("X-Notification-Client-Secret", "notify-secret-123")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verify(lookup, never()).execute(any(), any());
    }

    @Test
    void quaMotTramIdBi400() throws Exception {
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < 101; i++) ids.append(i == 0 ? "" : ",").append("\"").append(UUID.randomUUID()).append("\"");
        mvc.perform(post(PATH).header("X-Notification-Client-Secret", "notify-secret-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"" + TENANT + "\",\"userIds\":[" + ids + "]}"))
                .andExpect(status().isBadRequest());
        verify(lookup, never()).execute(any(), any());
    }

    @Test
    void userIdsVangVanHopLeDeLayTenGoiTenant() throws Exception {
        when(lookup.execute(any(), any())).thenReturn(new Result("Hiệu sách A", List.of()));
        mvc.perform(post(PATH).header("X-Notification-Client-Secret", "notify-secret-123")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"tenantId\":\"" + TENANT + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tenantDisplayName").value("Hiệu sách A"))
                .andExpect(jsonPath("$.recipients.length()").value(0));
    }

    @Test
    void loiNghiepVuTuUseCaseLa400() throws Exception {
        when(lookup.execute(any(), any())).thenThrow(new InvalidUserException("Tối đa 100 id mỗi lần tra"));
        mvc.perform(post(PATH).header("X-Notification-Client-Secret", "notify-secret-123")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void chiMotDuongDanCongKhaiCacDuongDanKhacVanCanToken() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/notification-recipients/other").header("X-Notification-Client-Secret", "notify-secret-123")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }
}
