package lms.identity.user.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import lms.identity.config.SecurityConfiguration;
import lms.identity.user.application.LookupNotificationRecipientsUseCase;
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

/** Chưa cấu hình NOTIFICATION_CLIENT_SECRET (rỗng) thì từ chối MỌI lời gọi, kể cả header rỗng khớp bí mật rỗng. */
@WebMvcTest(NotificationRecipientController.class)
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class})
@TestPropertySource(properties = {"lms.security.permission.enabled=true", "lms.notification.client-secret="})
class NotificationRecipientControllerEmptySecretTest {

    static final String BODY = "{\"tenantId\":\"" + UUID.randomUUID() + "\",\"userIds\":[]}";

    @Autowired MockMvc mvc;

    @MockitoBean LookupNotificationRecipientsUseCase lookup;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    @Test
    void secretRongThiTuChoiMoiLoiGoi() throws Exception {
        mvc.perform(post("/internal/notification-recipients/lookup").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/notification-recipients/lookup").header("X-Notification-Client-Secret", "")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/notification-recipients/lookup").header("X-Notification-Client-Secret", "bat-ky")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verify(lookup, never()).execute(any(), any());
    }
}
