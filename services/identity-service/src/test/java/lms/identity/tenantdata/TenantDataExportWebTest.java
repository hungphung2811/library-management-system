package lms.identity.tenantdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.identity.config.SecurityConfiguration;
import lms.identity.tenantdata.application.DownloadTenantDataExportUseCase;
import lms.identity.tenantdata.application.DownloadTenantDataExportUseCase.Download;
import lms.identity.tenantdata.application.ExportViews.ExportView;
import lms.identity.tenantdata.application.QueryTenantDataExportUseCase;
import lms.identity.tenantdata.application.RequestTenantDataExportUseCase;
import lms.identity.tenantdata.domain.ExportStatus;
import lms.identity.tenantdata.domain.TenantDataExportException;
import lms.identity.tenantdata.domain.TenantDataExportException.Reason;
import lms.identity.tenantdata.infrastructure.web.PlatformTenantDataController;
import lms.identity.tenantdata.infrastructure.web.TenantDataExceptionHandler;
import lms.identity.tenantdata.infrastructure.web.TenantDataExportController;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * API xuất dữ liệu tenant (ADR-0031 F2): khóa động TenantDataExportController/request|list|get|download (ADR-0003),
 * PlatformTenantDataController chỉ platformAdmin, token svc=tenant-data không vào được endpoint của người dùng, IDOR (404 cùng
 * thân), tải stream kèm no-store, ánh xạ lỗi, bật lại kiểm khóa bằng @TestPropertySource. Không cần Docker.
 */
@WebMvcTest({TenantDataExportController.class, PlatformTenantDataController.class})
@Import({SecurityConfiguration.class, TenantDataExceptionHandler.class,
        lms.identity.tenantdata.infrastructure.web.TenantDataRequestValidationHandler.class,
        PermissionAutoConfiguration.class})
@TestPropertySource(properties = {"lms.security.permission.enabled=true",
        "lms.security.permission.require-tenant=true"})
class TenantDataExportWebTest {

    @Autowired MockMvc mvc;

    @MockitoBean RequestTenantDataExportUseCase request;
    @MockitoBean QueryTenantDataExportUseCase query;
    @MockitoBean DownloadTenantDataExportUseCase download;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID EXPORT = UUID.randomUUID();
    private static final String BASE = "/internal/tenant-data/exports";
    private static final String PLATFORM = "/api/platform/tenants/" + TENANT + "/data-exports";
    private static final Instant AT = Instant.parse("2026-11-19T01:00:00Z");
    private static final ExportView VIEW = new ExportView(EXPORT, ExportStatus.PENDING, USER, false, null, AT, null,
            null, null, 0, 0, 0, null, null, 0, null, List.of());

    @BeforeEach
    void bindTenant() {
        TenantContext.set(TENANT);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private RequestPostProcessor tenantAdmin() {
        return jwt().jwt(j -> j.subject(USER.toString()).claim("admin", true).claim("tenantId", TENANT.toString())
                .claim("modules", List.of("LENDING")));
    }

    private RequestPostProcessor readOnlyAdmin() {
        return jwt().jwt(j -> j.subject(USER.toString()).claim("admin", true).claim("tenantId", TENANT.toString())
                .claim("tenantAccess", "READ_ONLY"));
    }

    private RequestPostProcessor platformAdmin() {
        return jwt().jwt(j -> j.subject(USER.toString()).claim("platformAdmin", true));
    }

    private RequestPostProcessor withKey(String key) {
        return jwt().jwt(j -> j.subject(USER.toString()).claim("tenantId", TENANT.toString())
                .claim("permissions", List.of(key))).authorities(new SimpleGrantedAuthority(key));
    }

    private RequestPostProcessor serviceToken() {
        return jwt().jwt(j -> j.subject(EXPORT.toString()).claim("svc", "tenant-data")
                .audience(List.of("lms-tenant-data")).claim("tenantId", TENANT.toString())
                .claim("permissions", List.of()));
    }

    private List<MockHttpServletRequestBuilder> tenantEndpoints() {
        return List.of(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{}"), get(BASE),
                get(BASE + "/" + EXPORT), get(BASE + "/" + EXPORT + "/download"));
    }

    @Test
    void khongTokenBi401() throws Exception {
        for (MockHttpServletRequestBuilder r : tenantEndpoints()) mvc.perform(r).andExpect(status().isUnauthorized());
        mvc.perform(get(PLATFORM)).andExpect(status().isUnauthorized());
    }

    @Test
    void nguoiThuongThieuKhoaBi403VaKhongCoTenantIdBi403TenantRequired() throws Exception {
        for (MockHttpServletRequestBuilder r : tenantEndpoints()) {
            mvc.perform(r.with(withKey("OtherController/other"))).andExpect(status().isForbidden());
        }
        RequestPostProcessor noTenant = jwt().jwt(j -> j.subject(USER.toString()).claim("admin", true));
        mvc.perform(get(BASE).with(noTenant)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
        verifyNoInteractions(request, query, download);
    }

    @Test
    void tokenDichVuTenantDataKhongVaoDuocEndpointNguoiDungKeCaKhiCoAudDung() throws Exception {
        for (MockHttpServletRequestBuilder r : tenantEndpoints()) {
            mvc.perform(r.with(serviceToken())).andExpect(status().isForbidden());
        }
        mvc.perform(get(PLATFORM).with(serviceToken())).andExpect(status().isForbidden());
        verifyNoInteractions(request, query, download);
    }

    @Test
    void khoaDongTungMethodRieng() throws Exception {
        when(query.list(TENANT)).thenReturn(List.of(VIEW));
        when(query.get(TENANT, EXPORT)).thenReturn(VIEW);
        mvc.perform(get(BASE).with(withKey("TenantDataExportController/list"))).andExpect(status().isOk());
        mvc.perform(get(BASE + "/" + EXPORT).with(withKey("TenantDataExportController/get")))
                .andExpect(status().isOk());
        // khóa của method khác không đủ
        mvc.perform(get(BASE).with(withKey("TenantDataExportController/get"))).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/" + EXPORT).with(withKey("TenantDataExportController/list")))
                .andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/" + EXPORT + "/download").with(withKey("TenantDataExportController/get")))
                .andExpect(status().isForbidden());
        mvc.perform(post(BASE).with(withKey("TenantDataExportController/list")).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isForbidden());
        verifyNoInteractions(request, download);
    }

    @Test
    void tenantAdminYeuCauTra202LayTenantTuTokenKhongTuBody() throws Exception {
        UUID other = UUID.randomUUID();
        when(request.request(eq(TENANT), eq(USER), eq(false), eq("chuyển hệ thống"))).thenReturn(VIEW);
        mvc.perform(post(BASE).with(tenantAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"chuyển hệ thống\",\"tenantId\":\"" + other + "\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(EXPORT.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));
        verify(request).request(eq(TENANT), eq(USER), eq(false), eq("chuyển hệ thống"));
    }

    @Test
    void bodyRongHoacKhongCoBodyVanDuocVaLyDoQuaDaiBi400() throws Exception {
        when(request.request(eq(TENANT), eq(USER), eq(false), any())).thenReturn(VIEW);
        mvc.perform(post(BASE).with(tenantAdmin())).andExpect(status().isAccepted());
        mvc.perform(post(BASE).with(tenantAdmin()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted());
        mvc.perform(post(BASE).with(tenantAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"" + "x".repeat(501) + "\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void chedoChiDocVanChoYeuCauXuatNhungKhongChoGhiKhac() throws Exception {
        when(request.request(eq(TENANT), eq(USER), eq(false), any())).thenReturn(VIEW);
        // @AllowedWhenReadOnly: tenant hết hạn vẫn lấy lại được dữ liệu của mình
        mvc.perform(post(BASE).with(readOnlyAdmin()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted());
        when(query.list(TENANT)).thenReturn(List.of());
        mvc.perform(get(BASE).with(readOnlyAdmin())).andExpect(status().isOk());
    }

    @Test
    void idorTenantKhacVaIdKhongTonTaiCungMaCungThan() throws Exception {
        UUID missing = UUID.randomUUID();
        TenantDataExportException notFound = new TenantDataExportException(Reason.EXPORT_NOT_FOUND,
                "Không tìm thấy gói xuất");
        when(query.get(TENANT, EXPORT)).thenThrow(notFound);
        when(query.get(TENANT, missing)).thenThrow(notFound);
        when(download.open(eq(TENANT), eq(EXPORT), eq(USER), eq(false))).thenThrow(notFound);

        String a = mvc.perform(get(BASE + "/" + EXPORT).with(tenantAdmin())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXPORT_NOT_FOUND")).andReturn().getResponse().getContentAsString();
        String b = mvc.perform(get(BASE + "/" + missing).with(tenantAdmin())).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String c = mvc.perform(get(BASE + "/" + EXPORT + "/download").with(tenantAdmin()))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        // thân giống nhau (trừ dấu thời gian của ErrorResponse): cùng code và cùng message, không phân biệt được hai trường hợp
        assertThat(stripTimestamp(a)).isEqualTo(stripTimestamp(b)).isEqualTo(stripTimestamp(c));
    }

    @Test
    void taiXuongPhatLuongZipKemNoStoreVaTenFileAnToan() throws Exception {
        byte[] zip = "PK-fake-zip-bytes".getBytes(StandardCharsets.UTF_8);
        when(download.open(eq(TENANT), eq(EXPORT), eq(USER), eq(false)))
                .thenReturn(new Download(new ByteArrayInputStream(zip), "tenant-data-" + EXPORT + ".zip", zip.length));
        mvc.perform(get(BASE + "/" + EXPORT + "/download").with(tenantAdmin())).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("Content-Length", String.valueOf(zip.length)))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.startsWith("attachment"),
                                org.hamcrest.Matchers.containsString("tenant-data-" + EXPORT + ".zip"))))
                .andExpect(content().bytes(zip));
    }

    @Test
    void anhXaMaLoiSangHttp() throws Exception {
        when(request.request(eq(TENANT), eq(USER), eq(false), any()))
                .thenThrow(new TenantDataExportException(Reason.EXPORT_IN_PROGRESS, "đang xử lý"))
                .thenThrow(new TenantDataExportException(Reason.EXPORT_RATE_LIMITED, "giới hạn"))
                .thenThrow(new TenantDataExportException(Reason.EXPORT_NOT_CONFIGURED, "chưa cấu hình"));
        MediaType json = MediaType.APPLICATION_JSON;
        mvc.perform(post(BASE).with(tenantAdmin()).contentType(json).content("{}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXPORT_IN_PROGRESS"));
        mvc.perform(post(BASE).with(tenantAdmin()).contentType(json).content("{}"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("EXPORT_RATE_LIMITED"));
        mvc.perform(post(BASE).with(tenantAdmin()).contentType(json).content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("EXPORT_NOT_CONFIGURED"));

        when(download.open(eq(TENANT), eq(EXPORT), eq(USER), eq(false)))
                .thenThrow(new TenantDataExportException(Reason.EXPORT_EXPIRED, "hết hạn"))
                .thenThrow(new TenantDataExportException(Reason.EXPORT_NOT_READY, "chưa sẵn sàng"));
        mvc.perform(get(BASE + "/" + EXPORT + "/download").with(tenantAdmin())).andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("EXPORT_EXPIRED"));
        mvc.perform(get(BASE + "/" + EXPORT + "/download").with(tenantAdmin())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXPORT_NOT_READY"));
    }

    // ---- platform ----

    @Test
    void platformChiChoPlatformAdminKhongDungKhoa() throws Exception {
        List<MockHttpServletRequestBuilder> endpoints = List.of(
                post(PLATFORM).contentType(MediaType.APPLICATION_JSON).content("{}"), get(PLATFORM),
                get(PLATFORM + "/" + EXPORT), get(PLATFORM + "/" + EXPORT + "/download"));
        for (MockHttpServletRequestBuilder r : endpoints) {
            mvc.perform(r.with(tenantAdmin())).andExpect(status().isForbidden());
            mvc.perform(r.with(withKey("PlatformTenantDataController/export"))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(request, query, download);
    }

    @Test
    void platformAdminXuatThayVaTaiLayTenantTuDuongDan() throws Exception {
        when(request.request(eq(TENANT), eq(USER), eq(true), eq("chấm dứt hợp đồng"))).thenReturn(VIEW);
        when(query.list(TENANT)).thenReturn(List.of(VIEW));
        when(download.open(eq(TENANT), eq(EXPORT), eq(USER), eq(true)))
                .thenReturn(new Download(new ByteArrayInputStream(new byte[] {1, 2}), "f.zip", 2));
        mvc.perform(post(PLATFORM).with(platformAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"chấm dứt hợp đồng\"}"))
                .andExpect(status().isAccepted());
        mvc.perform(get(PLATFORM).with(platformAdmin())).andExpect(status().isOk());
        mvc.perform(get(PLATFORM + "/" + EXPORT + "/download").with(platformAdmin())).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        verify(request).request(eq(TENANT), eq(USER), eq(true), eq("chấm dứt hợp đồng"));
        verify(download).open(eq(TENANT), eq(EXPORT), eq(USER), eq(true));
        // sanity: không có lời gọi nào đánh dấu tenant thường
        org.mockito.Mockito.verify(request, org.mockito.Mockito.never()).request(any(), any(), eq(false), any());
    }

    private static String stripTimestamp(String json) {
        return json.replaceAll("\"timestamp\":\"[^\"]*\"", "\"timestamp\":\"-\"");
    }
}
