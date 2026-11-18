package lms.circulation.tenantdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import lms.circulation.config.SecurityConfiguration;
import lms.circulation.tenantdata.infrastructure.web.TenantDataExportController;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import lms.tenant.TenantAutoConfiguration;
import lms.tenant.TenantContext;
import lms.tenant.export.TenantDataExporter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * ADR-0031 F1: {@code GET /internal/tenant-data/export} chỉ nhận token dịch vụ {@code svc = tenant-data} (aud
 * {@code lms-tenant-data}, có tenantId); admin, platform admin (kể cả X-Tenant-Id), integration, storefront, người dùng
 * thường và token tenant-data sai aud/thiếu tenant đều bị 403; không token 401. Kiểm khóa được bật như môi trường thật.
 */
@WebMvcTest(TenantDataExportController.class)
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class, TenantAutoConfiguration.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class TenantDataExportControllerTest {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final String URL = "/internal/tenant-data/export";

    @Autowired MockMvc mvc;

    @MockitoBean TenantDataExporter exporter;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    private static RequestPostProcessor tenantData(UUID tenant) {
        return jwt().jwt(j -> {
            j.audience(List.of("lms-tenant-data")).subject("identity").claim("svc", "tenant-data");
            if (tenant != null) j.claim("tenantId", tenant.toString());
        });
    }

    @Test
    void tokenTenantDataXuatZipVaTenantLayTuClaim() throws Exception {
        AtomicReference<UUID> seen = new AtomicReference<>();
        doAnswer(inv -> {
            seen.set(TenantContext.require());
            ((OutputStream) inv.getArgument(1)).write("ZIPDATA".getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(exporter).export(any(), any());

        mvc.perform(get(URL + "?tenantId=" + OTHER).header("X-Tenant-Id", OTHER.toString()).with(tenantData(TENANT)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string("ZIPDATA"));

        verify(exporter).export(eq(TENANT), any());
        assertThat(seen.get()).isEqualTo(TENANT).isNotEqualTo(OTHER);
    }

    @Test
    void moiTokenKhacBi403() throws Exception {
        RequestPostProcessor admin = jwt().jwt(j -> j.subject("u").claim("tenantId", TENANT.toString())
                .claim("admin", true).claim("permissions", List.of("*")));
        RequestPostProcessor platformAdmin = jwt().jwt(j -> j.subject("u").claim("platformAdmin", true)
                .claim("admin", true));
        RequestPostProcessor integration = jwt().jwt(j -> j.audience(List.of("lms-integration")).subject("app")
                .claim("tenantId", TENANT.toString()).claim("scope", "books:read inventory:read")
                .claim("application_id", UUID.randomUUID().toString()));
        RequestPostProcessor storefront = jwt().jwt(j -> j.audience(List.of("lms-storefront")).subject("shop")
                .claim("svc", "customer-api").claim("tenantId", TENANT.toString()));
        RequestPostProcessor user = jwt().jwt(j -> j.subject("u").claim("tenantId", TENANT.toString())
                .claim("permissions", List.of("TenantDataExportController/export")));
        RequestPostProcessor wrongAudience = jwt().jwt(j -> j.audience(List.of("lms-storefront")).subject("identity")
                .claim("svc", "tenant-data").claim("tenantId", TENANT.toString()));

        for (RequestPostProcessor token : List.of(admin, integration, storefront, user, wrongAudience)) {
            mvc.perform(get(URL).with(token)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        mvc.perform(get(URL).header("X-Tenant-Id", TENANT.toString()).with(platformAdmin))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verifyNoInteractions(exporter);
    }

    @Test
    void tokenTenantDataThieuTenantBi403() throws Exception {
        mvc.perform(get(URL).with(tenantData(null))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
        verifyNoInteractions(exporter);
    }

    @Test
    void khongTokenBi401() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        verifyNoInteractions(exporter);
    }

    @Test
    void chiMoGetKhongCoPhuongThucGhi() throws Exception {
        mvc.perform(post(URL).with(tenantData(TENANT))).andExpect(status().is4xxClientError());
        verifyNoInteractions(exporter);
    }
}
