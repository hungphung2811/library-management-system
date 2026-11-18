package lms.identity.auth.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import lms.identity.apiclient.application.CreateApplicationUseCase;
import lms.identity.apiclient.application.ManageApplicationDomainsUseCase;
import lms.identity.apiclient.application.ManageApplicationUseCase;
import lms.identity.apiclient.application.ManageCredentialsUseCase;
import lms.identity.apiclient.application.QueryApplicationUseCase;
import lms.identity.apiclient.infrastructure.web.ApplicationController;
import lms.identity.apiclient.infrastructure.web.ApplicationExceptionHandler;
import lms.identity.auth.application.IssueClientTokenUseCase;
import lms.identity.auth.application.IssueClientTokenUseCase.IssuedClientToken;
import lms.identity.auth.domain.ClientTokenException;
import lms.identity.auth.domain.ClientTokenException.Error;
import lms.identity.auth.infrastructure.web.OAuthTokenController;
import lms.identity.config.SecurityConfiguration;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Token endpoint OAuth2 client_credentials ở mức web (pha I2, T-I1): form + Basic, mã lỗi RFC 6749 mục 5.2, header
 * no-store, không lộ phân biệt lỗi client, và token tích hợp bị 403 ở endpoint thường (PermissionInterceptor). Bật lại
 * kiểm khóa bằng @TestPropertySource, mock PermissionKeyRegistrar. Không cần Docker.
 */
@ExtendWith(OutputCaptureExtension.class)
@WebMvcTest({OAuthTokenController.class, ApplicationController.class})
@Import({SecurityConfiguration.class, ApplicationExceptionHandler.class, PermissionAutoConfiguration.class})
@TestPropertySource(properties = {"lms.security.permission.enabled=true",
        "lms.security.permission.require-tenant=true"})
class OAuthTokenControllerTest {

    private static final String URL = "/internal/oauth2/token";
    private static final UUID APP = UUID.randomUUID();
    private static final String SECRET = "lms_sk_bi-mat-thu-nghiem-0123456789abcdefghijk";

    @Autowired MockMvc mvc;

    @MockitoBean IssueClientTokenUseCase issue;
    @MockitoBean CreateApplicationUseCase create;
    @MockitoBean QueryApplicationUseCase query;
    @MockitoBean ManageApplicationUseCase manage;
    @MockitoBean ManageCredentialsUseCase credentials;
    @MockitoBean ManageApplicationDomainsUseCase domains;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    @BeforeEach
    void ok() {
        when(issue.execute(anyString(), anyString(), anyString(), any()))
                .thenReturn(new IssuedClientToken("jwt-ky", 300, "books:read"));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private static MockHttpServletRequestBuilder form(String... pairs) {
        MockHttpServletRequestBuilder b = post(URL).contentType(MediaType.APPLICATION_FORM_URLENCODED);
        for (int i = 0; i < pairs.length; i += 2) b.param(pairs[i], pairs[i + 1]);
        return b;
    }

    private static String basic(String id, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void basicHopLeTraTokenTheoRfcVaKhongCache() throws Exception {
        mvc.perform(form("grant_type", "client_credentials", "scope", "books:read")
                        .header("Authorization", basic(APP.toString(), SECRET)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.access_token").value("jwt-ky"))
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.expires_in").value(300))
                .andExpect(jsonPath("$.scope").value("books:read"));

        verify(issue).execute("client_credentials", APP.toString(), SECRET, "books:read");
    }

    @Test
    void thongTinClientTrongThanHopLeVaKhongCanScope() throws Exception {
        mvc.perform(form("grant_type", "client_credentials", "client_id", APP.toString(), "client_secret", SECRET))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.access_token").value("jwt-ky"));

        verify(issue).execute(eq("client_credentials"), eq(APP.toString()), eq(SECRET), isNull());
    }

    @Test
    void basicMaHoaPhanTramDuocGiaiMa() throws Exception {
        String encoded = basic(APP.toString(), "a%2Bb%3Ac");

        mvc.perform(form("grant_type", "client_credentials").header("Authorization", encoded))
                .andExpect(status().isOk());

        verify(issue).execute("client_credentials", APP.toString(), "a+b:c", null);
    }

    @Test
    void congKhaiKhongCanJwtNhungDuongDanKhacVanCanXacThuc() throws Exception {
        mvc.perform(get("/internal/applications")).andExpect(status().isUnauthorized());
        mvc.perform(form("grant_type", "client_credentials", "client_id", APP.toString(), "client_secret", SECRET))
                .andExpect(status().isOk());
    }

    @Test
    void invalidClientLa401KemWwwAuthenticateVaCungMotThanChoMoiLyDo() throws Exception {
        when(issue.execute(anyString(), anyString(), anyString(), any()))
                .thenThrow(new ClientTokenException(Error.INVALID_CLIENT));

        String bodyA = mvc.perform(form("grant_type", "client_credentials", "client_id", APP.toString(),
                        "client_secret", "sai"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Basic")))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.error").value("invalid_client"))
                .andReturn().getResponse().getContentAsString();
        String bodyB = mvc.perform(form("grant_type", "client_credentials", "client_id", UUID.randomUUID().toString(),
                        "client_secret", SECRET))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();

        assertThat(bodyA).isEqualTo(bodyB).doesNotContain(APP.toString()).doesNotContain(SECRET);
    }

    @Test
    void thieuThongTinClientLaInvalidClient401() throws Exception {
        when(issue.execute(anyString(), isNull(), isNull(), any()))
                .thenThrow(new ClientTokenException(Error.INVALID_CLIENT));

        mvc.perform(form("grant_type", "client_credentials"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("WWW-Authenticate"))
                .andExpect(jsonPath("$.error").value("invalid_client"));
    }

    @Test
    void basicHongLaInvalidClient401KhongGoiUseCase() throws Exception {
        mvc.perform(form("grant_type", "client_credentials").header("Authorization", "Basic !!!khong-phai-base64"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_client"));
        mvc.perform(form("grant_type", "client_credentials").header("Authorization",
                        "Basic " + Base64.getEncoder().encodeToString("khong-co-dau-hai-cham".getBytes())))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(issue);
    }

    @Test
    void cacLoiTuUseCaseAnhXaDungMaVaTrangThai() throws Exception {
        record Case(Error error, int status, String code) { }
        for (Case c : List.of(new Case(Error.INVALID_REQUEST, 400, "invalid_request"),
                new Case(Error.UNSUPPORTED_GRANT_TYPE, 400, "unsupported_grant_type"),
                new Case(Error.INVALID_SCOPE, 400, "invalid_scope"))) {
            org.mockito.Mockito.doThrow(new ClientTokenException(c.error())).when(issue)
                    .execute(anyString(), anyString(), anyString(), any());

            mvc.perform(form("grant_type", "x", "client_id", APP.toString(), "client_secret", SECRET))
                    .andExpect(status().is(c.status()))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().doesNotExist("WWW-Authenticate"))
                    .andExpect(jsonPath("$.error").value(c.code()))
                    .andExpect(jsonPath("$.error_description").exists());
        }
    }

    @Test
    void thamSoTrenQueryStringBiTuChoiVaKhongGoiUseCase() throws Exception {
        mvc.perform(post(URL + "?client_secret=" + SECRET).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials").param("client_id", APP.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(header().string("Cache-Control", "no-store"));

        verifyNoInteractions(issue);
    }

    @Test
    void khongPhaiFormUrlEncodedLaInvalidRequest() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grant_type\":\"client_credentials\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
        mvc.perform(post(URL)).andExpect(status().isBadRequest());

        verifyNoInteractions(issue);
    }

    @Test
    void dungHaiPhuongThucXacThucHoacThamSoLapLaInvalidRequest() throws Exception {
        mvc.perform(form("grant_type", "client_credentials", "client_id", APP.toString())
                        .header("Authorization", basic(APP.toString(), SECRET)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
        mvc.perform(form("grant_type", "client_credentials", "client_id", APP.toString(), "client_secret", SECRET)
                        .param("grant_type", "client_credentials"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));

        verifyNoInteractions(issue);
    }

    @Test
    void phuongThucGetKhongDuocPhep() throws Exception {
        int s = mvc.perform(get(URL)).andReturn().getResponse().getStatus();

        assertThat(s).isIn(401, 403, 405);
    }

    @Test
    void khongLogSecretHayAuthorization(CapturedOutput output) throws Exception {
        when(issue.execute(anyString(), anyString(), anyString(), any()))
                .thenThrow(new ClientTokenException(Error.INVALID_CLIENT));

        mvc.perform(form("grant_type", "client_credentials").header("Authorization", basic(APP.toString(), SECRET)));
        mvc.perform(form("grant_type", "client_credentials", "client_id", APP.toString(), "client_secret", SECRET));

        assertThat(output.getAll()).doesNotContain(SECRET)
                .doesNotContain(Base64.getEncoder().encodeToString((APP + ":" + SECRET).getBytes()));
    }

    @Test
    void tokenIntegrationBi403OEndpointThuongKhongCoIntegrationScope() throws Exception {
        TenantContext.set(UUID.randomUUID());

        mvc.perform(get("/internal/applications").with(jwt().jwt(j -> j
                        .subject(APP.toString())
                        .claim("aud", List.of("lms-integration"))
                        .claim("tenantId", UUID.randomUUID().toString())
                        .claim("scope", "books:read orders:read inventory:read"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(query);
    }
}
