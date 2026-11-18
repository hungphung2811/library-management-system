package lms.sales.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
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
import lms.sales.config.SecurityConfiguration;
import lms.sales.settings.infrastructure.web.SalesSettingsController;
import lms.sales.support.HarnessBeans;
import lms.sales.tenantdata.application.ExportSalesTenantDataUseCase;
import lms.sales.tenantdata.infrastructure.web.TenantDataExportController;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import lms.tenant.TenantAutoConfiguration;
import org.junit.jupiter.api.BeforeEach;
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
 * ADR-0031 F1: endpoint {@code GET /internal/tenant-data/export} chỉ nhận token dịch vụ {@code svc=tenant-data}
 * (aud {@code lms-tenant-data}, có tenantId); tenant lấy từ claim; token tenant-data bỏ qua kiểm module SALES và khóa.
 * Mọi token khác (người dùng, admin, platform admin kể cả X-Tenant-Id, integration, storefront) bị 403 mà use case
 * không được gọi. Kiểm khóa bật như production.
 */
@WebMvcTest({TenantDataExportController.class, SalesSettingsController.class})
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class, TenantAutoConfiguration.class,
        HarnessBeans.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class TenantDataExportWebTest {

    private static final String URL = "/internal/tenant-data/export";
    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    @Autowired MockMvc mvc;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;
    @MockitoBean ExportSalesTenantDataUseCase useCase;

    @BeforeEach
    void stub() throws Exception {
        when(useCase.export(any(), any())).thenAnswer(inv -> {
            OutputStream out = inv.getArgument(1);
            out.write("PK-fake".getBytes(StandardCharsets.UTF_8));
            return new ExportSalesTenantDataUseCase.Summary(1, 0);
        });
    }

    private static RequestPostProcessor tenantData(UUID tenant) {
        return jwt().jwt(j -> {
            j.audience(List.of("lms-tenant-data")).subject("tenant-data").claim("svc", "tenant-data");
            if (tenant != null) j.claim("tenantId", tenant.toString());
        });
    }

    @Test
    void tokenTenantDataDocDuocTenantLayTuClaimVaTraZipKhongCache() throws Exception {
        mvc.perform(get(URL).with(tenantData(TENANT)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string("PK-fake"));
        verify(useCase).export(eq(TENANT), any());
    }

    @Test
    void tenantTrongQueryHoacHeaderKhongDuocDungLamTenantDich() throws Exception {
        mvc.perform(get(URL).param("tenantId", OTHER.toString()).header("X-Tenant-Id", OTHER.toString())
                .with(tenantData(TENANT))).andExpect(status().isOk());
        verify(useCase).export(eq(TENANT), any());
        verify(useCase, never()).export(eq(OTHER), any());
    }

    @Test
    void tokenTenantDataBoQuaKiemModuleVaKhoa() throws Exception {
        // token không có claim modules/permissions/admin nhưng vẫn vào (tenant đã thu hồi module vẫn xuất được)
        mvc.perform(get(URL).with(tenantData(TENANT))).andExpect(status().isOk());
    }

    @Test
    void tokenTenantDataThieuTenantLa403TenantRequired() throws Exception {
        mvc.perform(get(URL).with(tenantData(null))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
        verifyNoInteractions(useCase);
    }

    @Test
    void tokenTenantDataSaiAudienceLa403() throws Exception {
        mvc.perform(get(URL).with(jwt().jwt(j -> j.claim("svc", "tenant-data").claim("tenantId", TENANT.toString())
                        .audience(List.of("lms-storefront")))))
                .andExpect(status().isForbidden());
        mvc.perform(get(URL).with(jwt().jwt(j -> j.claim("svc", "tenant-data").claim("tenantId", TENANT.toString()))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(useCase);
    }

    @Test
    void khongTokenLa401() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        verifyNoInteractions(useCase);
    }

    @Test
    void moiTokenKhacLa403KhongLoKhoaVaUseCaseKhongDuocGoi() throws Exception {
        List<RequestPostProcessor> others = List.of(
                // quản trị tenant
                jwt().jwt(j -> j.claim("admin", true).claim("tenantId", TENANT.toString())
                        .claim("modules", List.of("SALES"))),
                // người dùng có đủ khóa của mọi endpoint sales
                jwt().jwt(j -> j.claim("tenantId", TENANT.toString()).claim("modules", List.of("SALES"))
                        .claim("permissions", List.of("TenantDataExportController/export"))),
                // platform admin, có và không X-Tenant-Id (header thêm ở dưới)
                jwt().jwt(j -> j.claim("platformAdmin", true)),
                // integration
                jwt().jwt(j -> j.audience(List.of("lms-integration")).claim("tenantId", TENANT.toString())
                        .claim("modules", List.of("SALES"))
                        .claim("scope", "books:read books:write inventory:read inventory:write orders:read "
                                + "orders:write customers:read customers:write")
                        .claim("application_id", UUID.randomUUID().toString())),
                // storefront (token dịch vụ của customer-api) và token khách storefront
                jwt().jwt(j -> j.audience(List.of("lms-storefront")).claim("svc", "customer-api")
                        .claim("tenantId", TENANT.toString()).claim("modules", List.of("SALES"))),
                jwt().jwt(j -> j.audience(List.of("lms-storefront")).claim("tenantId", TENANT.toString())
                        .claim("modules", List.of("SALES"))));
        for (RequestPostProcessor token : others) {
            String body = mvc.perform(get(URL).with(token).header("X-Tenant-Id", TENANT.toString()))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("Controller/");
            mvc.perform(get(URL).with(token)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(useCase);
    }

    @Test
    void tokenTenantDataKhongVaoDuocEndpointThuong() throws Exception {
        mvc.perform(get("/internal/sales/settings").with(tenantData(TENANT))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void chiNhanGetCacPhuongThucGhiKhongTonTai() throws Exception {
        int status = mvc.perform(post(URL).with(tenantData(TENANT))).andReturn().getResponse().getStatus();
        assertThat(status).isIn(403, 405);
        verifyNoInteractions(useCase);
    }
}
