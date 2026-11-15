package lms.identity.accesscontrol.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import lms.identity.accesscontrol.application.RegisterPermissionKeyUseCase;
import lms.identity.accesscontrol.infrastructure.web.InternalPermissionController;
import lms.identity.config.SecurityConfiguration;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Chưa cấu hình app.internal-api-key thì đóng theo mặc định: từ chối cả khi header rỗng. */
@WebMvcTest(InternalPermissionController.class)
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class,
        InternalPermissionControllerNoKeySecurityTest.Cfg.class})
@TestPropertySource(properties = {"lms.security.permission.enabled=true", "app.internal-api-key="})
class InternalPermissionControllerNoKeySecurityTest {

    @TestConfiguration
    static class Cfg {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    @Autowired MockMvc mvc;

    @MockitoBean RegisterPermissionKeyUseCase register;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    @Test
    void chuaCauHinhKhoaThiTuChoiMoiLoiGoi() throws Exception {
        String path = "/internal/permissions/registrations";
        String body = InternalPermissionControllerSecurityTest.BODY;
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(path).header("X-Internal-Api-Key", "").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(path).header("X-Internal-Api-Key", "bat-ky").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        verify(register, never()).execute(any());
    }
}
