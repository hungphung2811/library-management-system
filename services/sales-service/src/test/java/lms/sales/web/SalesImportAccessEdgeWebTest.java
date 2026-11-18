package lms.sales.web;

import static lms.sales.support.WebTokens.TENANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;
import lms.imports.core.ImportSchema;
import lms.imports.web.ImportExceptionHandler;
import lms.imports.web.MultipartLimitHandler;
import lms.sales.config.SecurityConfiguration;
import lms.sales.stock.application.imports.StockItemImportHandler;
import lms.sales.stock.application.imports.SupplierImportHandler;
import lms.sales.stock.infrastructure.web.StockItemImportController;
import lms.sales.stock.infrastructure.web.SupplierImportController;
import lms.sales.support.HarnessBeans;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import lms.tenant.TenantAutoConfiguration;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Kiểm thử độc lập truy cập phiên nhập (ADR-0024 mục 4): tenant khác, platform admin có/không {@code X-Tenant-Id},
 * module bị gỡ giữa upload và confirm, tải mẫu/phiên khi token không tenant. Dùng cấu hình thật của SalesImportWebTest.
 */
@WebMvcTest({StockItemImportController.class, SupplierImportController.class})
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class, TenantAutoConfiguration.class,
        HarnessBeans.class, SalesImportWebTest.Beans.class, SalesImportAccessEdgeWebTest.Binder.class, ImportExceptionHandler.class, MultipartLimitHandler.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class SalesImportAccessEdgeWebTest {

    /**
     * Auto-config thật đặt TenantScopeBinder của tenant-common (before=PermissionAutoConfiguration); trong @WebMvcTest
     * với @Import thủ công thì binder NOOP thắng nên platform admin + X-Tenant-Id không bind được tenant: dựng lại ở đây.
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class Binder {
        @org.springframework.context.annotation.Bean
        lms.security.permission.TenantScopeBinder tenantScopeBinder() {
            return new lms.security.permission.TenantScopeBinder() {
                @Override
                public void bindAll() {
                    lms.tenant.TenantContext.bindAllTenants();
                }

                @Override
                public void bindSingle(UUID tenantId) {
                    lms.tenant.TenantContext.set(tenantId);
                }
            };
        }
    }

    private static final String SUPPLIERS = "/internal/imports/suppliers";
    private static final String SUP_UPLOAD = "SupplierImportController/upload";
    private static final String SUP_CONFIRM = "SupplierImportController/confirm";

    @Autowired MockMvc mvc;
    @Autowired SupplierImportHandler supplierHandler;
    @Autowired ObjectMapper json;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    private final UUID sub = UUID.randomUUID();

    private RequestPostProcessor tenantUser(UUID tenant, List<String> modules, String... keys) {
        return jwt().jwt(j -> j.subject(sub.toString()).claim("tenantId", tenant.toString())
                .claim("permissions", List.of(keys)).claim("modules", modules))
                .authorities(java.util.Arrays.stream(keys)
                        .<org.springframework.security.core.GrantedAuthority>map(
                                org.springframework.security.core.authority.SimpleGrantedAuthority::new).toList());
    }

    private RequestPostProcessor platformAdmin(UUID platformSub) {
        return jwt().jwt(j -> j.subject(platformSub.toString()).claim("platformAdmin", true));
    }

    private byte[] xlsx(ImportSchema schema, String... names) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var spec = schema.sheets().get(0);
            Sheet s = wb.createSheet(spec.name());
            Row head = s.createRow(0);
            for (int c = 0; c < spec.columns().size(); c++) head.createCell(c).setCellValue(spec.columns().get(c).label());
            int r = 1;
            for (String n : names) s.createRow(r++).createCell(0).setCellValue(n);
            Sheet meta = wb.createSheet("_meta");
            meta.createRow(0).createCell(0).setCellValue("type");
            meta.getRow(0).createCell(1).setCellValue(schema.type());
            wb.write(out);
            return out.toByteArray();
        }
    }

    private String upload(RequestPostProcessor who, String... names) throws Exception {
        MockMultipartFile f = new MockMultipartFile("file", "n.xlsx", "application/octet-stream",
                xlsx(supplierHandler.schema(), names));
        String body = mvc.perform(multipart(SUPPLIERS).file(f).with(who)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("importId").asText();
    }

    @Test
    void sameUserInAnotherTenantSeesNoSessionOnAnyEndpoint() throws Exception {
        String id = upload(tenantUser(TENANT, List.of("SALES"), SUP_UPLOAD), "NCC Tenant A");
        UUID other = UUID.randomUUID();
        RequestPostProcessor otherTenant = tenantUser(other, List.of("SALES"), SUP_UPLOAD, SUP_CONFIRM);

        mvc.perform(get(SUPPLIERS + "/" + id).with(otherTenant)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("IMPORT_NOT_FOUND"));
        mvc.perform(get(SUPPLIERS + "/" + id + "/rows").with(otherTenant)).andExpect(status().isNotFound());
        mvc.perform(get(SUPPLIERS + "/" + id + "/error-file").with(otherTenant)).andExpect(status().isNotFound());
        mvc.perform(post(SUPPLIERS + "/" + id + "/confirm").with(otherTenant)).andExpect(status().isNotFound());
        mvc.perform(delete(SUPPLIERS + "/" + id).with(otherTenant)).andExpect(status().isNotFound());
        // phiên gốc còn nguyên sau các lần thử của tenant khác
        mvc.perform(get(SUPPLIERS + "/" + id).with(tenantUser(TENANT, List.of("SALES")))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"));
    }

    @Test
    void moduleRemovedBetweenUploadAndConfirmBlocksConfirmAndKeepsTheSession() throws Exception {
        String id = upload(tenantUser(TENANT, List.of("SALES"), SUP_UPLOAD), "NCC Mất Module");

        mvc.perform(post(SUPPLIERS + "/" + id + "/confirm").with(tenantUser(TENANT, List.of("LENDING"), SUP_CONFIRM)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MODULE_NOT_ENABLED"));
        mvc.perform(get(SUPPLIERS + "/" + id).with(tenantUser(TENANT, List.of("SALES"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VALIDATED"));
    }

    @Test
    void platformAdminWithoutTenantHeaderIsRejectedWith403NeverServerError() throws Exception {
        RequestPostProcessor pa = platformAdmin(UUID.randomUUID());
        UUID id = UUID.randomUUID();
        mvc.perform(get(SUPPLIERS + "/template").with(pa)).andExpect(status().is4xxClientError());
        mvc.perform(get(SUPPLIERS + "/" + id).with(pa)).andExpect(status().isForbidden());
        mvc.perform(get(SUPPLIERS + "/" + id + "/rows").with(pa)).andExpect(status().isForbidden());
        mvc.perform(delete(SUPPLIERS + "/" + id).with(pa)).andExpect(status().isForbidden());
        mvc.perform(get(SUPPLIERS + "/" + id + "/error-file").with(pa)).andExpect(status().isForbidden());
        mvc.perform(post(SUPPLIERS + "/" + id + "/confirm").with(pa)).andExpect(status().isForbidden());
        MockMultipartFile f = new MockMultipartFile("file", "n.xlsx", "application/octet-stream",
                xlsx(supplierHandler.schema(), "NCC PA"));
        mvc.perform(multipart(SUPPLIERS).file(f).with(pa)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
    }

    @Test
    void platformAdminWithHeaderWorksOnTheTargetTenantOnlyAndIsInvisibleToTenantUsers() throws Exception {
        UUID paSub = UUID.randomUUID();
        MockMultipartFile f = new MockMultipartFile("file", "n.xlsx", "application/octet-stream",
                xlsx(supplierHandler.schema(), "NCC Platform"));
        var mr = mvc.perform(multipart(SUPPLIERS).file(f).with(platformAdmin(paSub))
                .header("X-Tenant-Id", TENANT.toString())).andReturn();
        var res = mr.getResponse();
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(201);
        String body = res.getContentAsString();
        String id = json.readTree(body).get("importId").asText();

        // cùng platform admin, cùng tenant đích: thấy phiên và confirm được (bỏ qua kiểm khóa)
        mvc.perform(get(SUPPLIERS + "/" + id).with(platformAdmin(paSub)).header("X-Tenant-Id", TENANT.toString()))
                .andExpect(status().isOk());
        // trỏ tenant đích khác: không thấy
        mvc.perform(get(SUPPLIERS + "/" + id).with(platformAdmin(paSub)).header("X-Tenant-Id", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
        // platform admin khác (sub khác) cùng tenant đích: không thấy (khóa phiên theo sub)
        mvc.perform(get(SUPPLIERS + "/" + id).with(platformAdmin(UUID.randomUUID())).header("X-Tenant-Id", TENANT.toString()))
                .andExpect(status().isNotFound());
        // người dùng tenant (sub khác) không thấy
        mvc.perform(get(SUPPLIERS + "/" + id).with(tenantUser(TENANT, List.of("SALES")))).andExpect(status().isNotFound());
        // header X-Tenant-Id của người không phải platform admin bị bỏ qua: không đổi sang tenant khác
        mvc.perform(get(SUPPLIERS + "/" + id).with(tenantUser(TENANT, List.of("SALES")))
                .header("X-Tenant-Id", UUID.randomUUID().toString())).andExpect(status().isNotFound());
        JsonNode view = json.readTree(mvc.perform(get(SUPPLIERS + "/" + id).with(platformAdmin(paSub))
                .header("X-Tenant-Id", TENANT.toString())).andReturn().getResponse().getContentAsString());
        assertThat(view.get("status").asText()).isEqualTo("VALIDATED");
    }

    @Test
    void tokenWithoutTenantAndNotPlatformAdminGets403OnReadEndpointsToo() throws Exception {
        RequestPostProcessor noTenant = jwt().jwt(j -> j.subject(sub.toString()).claim("modules", List.of("SALES")));
        mvc.perform(get(SUPPLIERS + "/" + UUID.randomUUID()).with(noTenant)).andExpect(status().isForbidden());
        mvc.perform(get(SUPPLIERS + "/" + UUID.randomUUID() + "/rows").with(noTenant)).andExpect(status().isForbidden());
    }

    @Test
    void malformedImportIdIsNotFoundNotServerError() throws Exception {
        mvc.perform(get(SUPPLIERS + "/not-a-uuid").with(tenantUser(TENANT, List.of("SALES")))).andExpect(status().isNotFound());
        mvc.perform(post(SUPPLIERS + "/not-a-uuid/confirm").with(tenantUser(TENANT, List.of("SALES"), SUP_CONFIRM)))
                .andExpect(status().isNotFound());
    }

    @Test
    void stockItemTemplateAndStockControllerAlsoNeedSalesModule() throws Exception {
        mvc.perform(get("/internal/imports/stock-items/template").with(tenantUser(TENANT, List.of("LENDING"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/internal/imports/stock-items/template").with(tenantUser(TENANT, List.of("SALES"))))
                .andExpect(status().isOk());
        assertThat(StockItemImportHandler.TYPE).isEqualTo("stock-items");
    }
}
