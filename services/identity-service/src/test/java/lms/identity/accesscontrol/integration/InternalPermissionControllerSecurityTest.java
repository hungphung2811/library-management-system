package lms.identity.accesscontrol.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import lms.identity.accesscontrol.application.AccessCommands.RegisterPermissionKey;
import lms.identity.accesscontrol.application.RegisterPermissionKeyUseCase;
import lms.identity.accesscontrol.infrastructure.web.InternalPermissionController;
import lms.identity.config.SecurityConfiguration;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

/** POST /internal/permissions/registrations: công khai ở lớp xác thực, bảo vệ bằng X-Internal-Api-Key. */
@WebMvcTest(InternalPermissionController.class)
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class,
        InternalPermissionControllerSecurityTest.FixedClock.class})
@TestPropertySource(properties = {"lms.security.permission.enabled=true", "app.internal-api-key=secret-key-123"})
class InternalPermissionControllerSecurityTest {

    static final String PATH = "/internal/permissions/registrations";
    static final String BODY = "{\"service\":\"catalog\",\"key\":\"BookController/create\",\"source\":\"AUTO\","
            + "\"httpMethod\":\"POST\",\"pathPattern\":\"/internal/books\",\"description\":\"Tạo sách\"}";
    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired MockMvc mvc;

    @MockitoBean RegisterPermissionKeyUseCase register;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    @Test
    void dungKhoaNoiBoKhongCanJwtTra204VaGoiUseCaseDungDuLieu() throws Exception {
        mvc.perform(post(PATH).header("X-Internal-Api-Key", "secret-key-123")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNoContent());

        ArgumentCaptor<RegisterPermissionKey> cmd = ArgumentCaptor.forClass(RegisterPermissionKey.class);
        verify(register).execute(cmd.capture());
        org.junit.jupiter.api.Assertions.assertEquals("catalog", cmd.getValue().service());
        org.junit.jupiter.api.Assertions.assertEquals("BookController/create", cmd.getValue().key());
        org.junit.jupiter.api.Assertions.assertEquals("POST", cmd.getValue().httpMethod());
        org.junit.jupiter.api.Assertions.assertEquals("/internal/books", cmd.getValue().pathPattern());
        org.junit.jupiter.api.Assertions.assertEquals(NOW, cmd.getValue().seenAt());
    }

    @Test
    void thieuHeaderTra401VaKhongGhi() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verify(register, never()).execute(any());
    }

    @Test
    void saiKhoaTra401VaKhongGhi() throws Exception {
        mvc.perform(post(PATH).header("X-Internal-Api-Key", "sai-khoa")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        // khác độ dài và khác hoa thường đều bị từ chối
        mvc.perform(post(PATH).header("X-Internal-Api-Key", "SECRET-KEY-123")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).header("X-Internal-Api-Key", "")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verify(register, never()).execute(any());
    }

    @Test
    void jwtHopLeKhongThayChoKhoaNoiBo() throws Exception {
        mvc.perform(post(PATH).with(jwt().jwt(j -> j.claim("admin", true)))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verify(register, never()).execute(any());
    }

    @Test
    void bodyHongTra400() throws Exception {
        mvc.perform(post(PATH).header("X-Internal-Api-Key", "secret-key-123")
                        .contentType(MediaType.APPLICATION_JSON).content("{khong-phai-json"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(PATH).header("X-Internal-Api-Key", "secret-key-123")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        verify(register, never()).execute(any());
    }

    @Test
    void useCaseNemLoiMienThiQuaHandlerTra422() throws Exception {
        org.mockito.Mockito.doThrow(new lms.identity.accesscontrol.domain.InvalidAccessControlException("khóa rỗng"))
                .when(register).execute(any());

        mvc.perform(post(PATH).header("X-Internal-Api-Key", "secret-key-123")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_ACCESS_CONTROL"));
    }

    @Test
    void chiMotDuongDanNoiBoCongKhaiCacDuongDanKhacVanCanToken() throws Exception {
        mvc.perform(get("/internal/permissions/registrations")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/permissions/other").header("X-Internal-Api-Key", "secret-key-123")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }
}
