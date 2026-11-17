package lms.identity.contract.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lms.identity.config.SecurityConfiguration;
import lms.identity.contract.application.ContractPaymentUseCase;
import lms.identity.contract.application.ContractQueryUseCase;
import lms.identity.contract.application.ContractResponses.ContractResponse;
import lms.identity.contract.application.ContractResponses.PaymentResponse;
import lms.identity.contract.application.ContractResponses.TenantContractView;
import lms.identity.contract.application.GetMyContractsUseCase;
import lms.identity.contract.application.ManageContractUseCase;
import lms.identity.contract.domain.ContractException;
import lms.identity.contract.domain.ContractException.Reason;
import lms.identity.contract.infrastructure.web.ContractExceptionHandler;
import lms.identity.contract.infrastructure.web.PlatformContractController;
import lms.identity.contract.infrastructure.web.PlatformContractPaymentController;
import lms.identity.contract.infrastructure.web.TenantContractController;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * T2 (ADR-0012): API platform chỉ dành cho platformAdmin; API tenant chỉ đọc, khóa động, DTO không lộ trường nội bộ;
 * thanh toán không có PUT/DELETE; ánh xạ mã lỗi. Bật lại kiểm khóa bằng @TestPropertySource. Không cần Docker.
 */
@WebMvcTest({PlatformContractController.class, PlatformContractPaymentController.class, TenantContractController.class})
@Import({SecurityConfiguration.class, ContractExceptionHandler.class, PermissionAutoConfiguration.class})
@TestPropertySource(properties = {"lms.security.permission.enabled=true",
        "lms.security.permission.require-tenant=true"})
class ContractSecurityTest {

    @Autowired MockMvc mvc;

    @MockitoBean ManageContractUseCase manage;
    @MockitoBean ContractQueryUseCase query;
    @MockitoBean ContractPaymentUseCase payments;
    @MockitoBean GetMyContractsUseCase mine;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    private static final UUID OPERATOR = UUID.randomUUID();
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID CONTRACT = UUID.randomUUID();
    private static final UUID PAYMENT = UUID.randomUUID();
    private static final String BODY = "{\"name\":\"Gói A\",\"modules\":[\"LENDING\"],\"startDate\":\"2026-12-01\","
            + "\"endDate\":\"2026-12-31\",\"totalAmount\":1000000}";

    private RequestPostProcessor platformAdmin() {
        return jwt().jwt(j -> j.subject(OPERATOR.toString()).claim("platformAdmin", true));
    }

    private RequestPostProcessor tenantAdmin() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("admin", true)
                .claim("tenantId", TENANT.toString()).claim("modules", List.of("LENDING")));
    }

    private static ContractResponse response() {
        return new ContractResponse(CONTRACT, TENANT, "Gói A", "DRAFT", null, false, false, List.of("LENDING"),
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 7), 1_000_000, 0, 1_000_000,
                null, "nội bộ", null, null, null, Instant.parse("2026-11-17T00:00:00Z"), OPERATOR,
                Instant.parse("2026-11-17T00:00:00Z"), OPERATOR, 0);
    }

    private List<MockHttpServletRequestBuilder> platformEndpoints() {
        String c = "/api/platform/contracts/" + CONTRACT;
        MediaType json = MediaType.APPLICATION_JSON;
        return List.of(
                post("/api/platform/tenants/" + TENANT + "/contracts").contentType(json).content(BODY),
                get("/api/platform/tenants/" + TENANT + "/contracts"),
                get("/api/platform/contracts/expiring"),
                get(c), put(c).contentType(json).content(BODY),
                post(c + "/activate"),
                post(c + "/extend").contentType(json).content("{\"endDate\":\"2027-01-31\",\"version\":0}"),
                post(c + "/cancel").contentType(json).content("{\"reason\":\"x\"}"),
                post(c + "/renew").contentType(json).content(BODY),
                get(c + "/history"),
                post(c + "/payments").contentType(json).content("{\"paidOn\":\"2026-12-01\",\"amount\":10}"),
                post(c + "/payments/" + PAYMENT + "/reverse").contentType(json).content("{}"));
    }

    @Test
    void platformKhongTokenBi401() throws Exception {
        for (MockHttpServletRequestBuilder request : platformEndpoints()) {
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void quanTriVienTenantGoiApiPlatformBi403VaKhongGoiUseCase() throws Exception {
        for (MockHttpServletRequestBuilder request : platformEndpoints()) {
            mvc.perform(request.with(tenantAdmin())).andExpect(status().isForbidden());
        }
        verifyNoInteractions(manage, query, payments);
    }

    @Test
    void platformAdminTaoHopDongNhan201VaGoiUseCaseVoiActor() throws Exception {
        when(manage.create(eq(TENANT), any(), eq(OPERATOR))).thenReturn(response());
        mvc.perform(post("/api/platform/tenants/" + TENANT + "/contracts").with(platformAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(CONTRACT.toString()))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.internalNote").value("nội bộ"))
                .andExpect(jsonPath("$.totalAmount").value(1_000_000));
    }

    @Test
    void maLoiHopDongAnhXaDungHttp() throws Exception {
        assertError(Reason.INVALID_CONTRACT, 400);
        assertError(Reason.CONTRACT_NOT_FOUND, 404);
        assertError(Reason.CONTRACT_OVERLAP, 409);
        assertError(Reason.CONTRACT_NOT_EDITABLE, 409);
        assertError(Reason.CONTRACT_VERSION_CONFLICT, 409);
        assertError(Reason.PAYMENT_ALREADY_REVERSED, 409);
        assertError(Reason.PAYMENT_NOT_ALLOWED, 409);
        assertError(Reason.PAYMENT_NOT_FOUND, 404);
    }

    private void assertError(Reason reason, int httpStatus) throws Exception {
        when(manage.activate(eq(CONTRACT), eq(OPERATOR))).thenThrow(new ContractException(reason, "lỗi"));
        mvc.perform(post("/api/platform/contracts/" + CONTRACT + "/activate").with(platformAdmin()))
                .andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.code").value(reason.name()));
    }

    @Test
    void thanhToanChiCoGhiVaDaoKhongCoPutHoacDelete() throws Exception {
        when(payments.record(eq(CONTRACT), any(), eq(OPERATOR))).thenReturn(new PaymentResponse(PAYMENT,
                LocalDate.of(2026, 12, 1), 10, null, null, null, null, OPERATOR, Instant.parse("2026-12-01T00:00:00Z")));
        mvc.perform(post("/api/platform/contracts/" + CONTRACT + "/payments").with(platformAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"paidOn\":\"2026-12-01\",\"amount\":10}"))
                .andExpect(status().isCreated());
        // không có mapping sửa/xóa: 405 (đường dẫn tồn tại ở POST) hoặc 404
        mvc.perform(put("/api/platform/contracts/" + CONTRACT + "/payments/" + PAYMENT).with(platformAdmin())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().is4xxClientError());
        mvc.perform(delete("/api/platform/contracts/" + CONTRACT + "/payments/" + PAYMENT).with(platformAdmin()))
                .andExpect(status().is4xxClientError());
        for (Method m : PlatformContractPaymentController.class.getDeclaredMethods()) {
            assertThat(m.isAnnotationPresent(PutMapping.class)).isFalse();
            assertThat(m.isAnnotationPresent(DeleteMapping.class)).isFalse();
        }
        assertThat(PlatformContractPaymentController.class.getAnnotation(RequestMapping.class)).isNotNull();
    }

    // ---------------------------------------------------------------- tenant admin chỉ đọc

    @Test
    void tenantKhongTokenBi401() throws Exception {
        mvc.perform(get("/internal/contracts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/contracts/current")).andExpect(status().isUnauthorized());
    }

    @Test
    void tenantAdminXemDuocVaJsonKhongLoTruongNoiBo() throws Exception {
        TenantContractView view = new TenantContractView(CONTRACT, "Gói A", "ACTIVE", "IN_EFFECT", false, LocalDate.of(2027, 1, 7),
                List.of("LENDING"), LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31), 1_000_000, 400_000, 600_000, null);
        when(mine.list()).thenReturn(List.of(view));
        when(mine.current()).thenReturn(view);

        String list = mvc.perform(get("/internal/contracts").with(tenantAdmin())).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].totalAmount").value(1_000_000))
                .andExpect(jsonPath("$[0].paidAmount").value(400_000))
                .andExpect(jsonPath("$[0].outstandingAmount").value(600_000))
                .andReturn().getResponse().getContentAsString();
        String current = mvc.perform(get("/internal/contracts/current").with(tenantAdmin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (String json : List.of(list, current)) {
            assertThat(json).doesNotContain("internalNote", "payments", "modulesOutsideContract", "retainedModule",
                    "cancelReason", "legacy", "reference", "modulePrice", "prices");
        }
    }

    @Test
    void nguoiThuongThieuKhoaBi403VaKhongCoTenantIdBi403TenantRequired() throws Exception {
        RequestPostProcessor reader = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                .claim("tenantId", TENANT.toString()).claim("permissions", List.of("OtherController/other")));
        mvc.perform(get("/internal/contracts").with(reader)).andExpect(status().isForbidden());
        mvc.perform(get("/internal/contracts/current").with(reader)).andExpect(status().isForbidden());

        RequestPostProcessor noTenant = jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("admin", true));
        mvc.perform(get("/internal/contracts").with(noTenant)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
        verifyNoInteractions(mine);
    }

    @Test
    void khoaDuocGanChoRoleKhacDiQuaVaKhongCoHopDongTraVe404() throws Exception {
        RequestPostProcessor accountant = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                .claim("tenantId", TENANT.toString())
                .claim("permissions", List.of("TenantContractController/current")))
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                        "TenantContractController/current"));
        when(mine.current()).thenThrow(new ContractException(Reason.CONTRACT_NOT_FOUND, "chưa có hợp đồng"));
        mvc.perform(get("/internal/contracts/current").with(accountant)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONTRACT_NOT_FOUND"));
        // có khóa current nhưng không có khóa list
        mvc.perform(get("/internal/contracts").with(accountant)).andExpect(status().isForbidden());
    }

    @Test
    void apiTenantKhongCoMappingGhi() throws Exception {
        mvc.perform(post("/internal/contracts").with(tenantAdmin()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().is4xxClientError());
        mvc.perform(delete("/internal/contracts/" + CONTRACT).with(tenantAdmin())).andExpect(status().is4xxClientError());
    }
}
