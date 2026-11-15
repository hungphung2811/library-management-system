package lms.identity.department.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import lms.identity.config.SecurityConfiguration;
import lms.identity.department.application.ChangeDepartmentStatusUseCase;
import lms.identity.department.application.CreateDepartmentUseCase;
import lms.identity.department.application.DepartmentNode;
import lms.identity.department.application.DepartmentResponse;
import lms.identity.department.application.GetDepartmentUseCase;
import lms.identity.department.application.ListDepartmentsUseCase;
import lms.identity.department.application.UpdateDepartmentUseCase;
import lms.identity.department.domain.DepartmentNotFoundException;
import lms.identity.department.domain.DepartmentStatus;
import lms.identity.department.domain.DuplicateDepartmentException;
import lms.identity.department.domain.InvalidDepartmentException;
import lms.identity.department.infrastructure.web.DepartmentController;
import lms.identity.department.infrastructure.web.DepartmentExceptionHandler;
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

/** DepartmentController: khóa DepartmentController/method, admin bỏ qua khóa, cần tenant, validation và lỗi domain. */
@WebMvcTest(DepartmentController.class)
@Import({SecurityConfiguration.class, DepartmentExceptionHandler.class, PermissionAutoConfiguration.class})
@TestPropertySource(properties = {"lms.security.permission.enabled=true",
        "lms.security.permission.require-tenant=true"})
class DepartmentControllerSecurityTest {

    @Autowired MockMvc mvc;

    @MockitoBean CreateDepartmentUseCase create;
    @MockitoBean UpdateDepartmentUseCase update;
    @MockitoBean ChangeDepartmentStatusUseCase changeStatus;
    @MockitoBean GetDepartmentUseCase get;
    @MockitoBean ListDepartmentsUseCase list;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    private static final String TENANT = UUID.randomUUID().toString();
    private static final UUID ID = UUID.randomUUID();
    private static final String CREATE_BODY = "{\"code\":\"IT\",\"name\":\"Phòng CNTT\"}";
    private static final String UPDATE_BODY = "{\"name\":\"Tên mới\"}";
    private static final String STATUS_BODY = "{\"status\":\"INACTIVE\"}";

    private static DepartmentResponse resp() {
        return new DepartmentResponse(ID, "IT", "Phòng CNTT", null, DepartmentStatus.ACTIVE);
    }

    private static RequestPostProcessor withKey(String... keys) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("tenantId", TENANT)
                .claim("permissions", List.of(keys)))
                .authorities(java.util.Arrays.stream(keys).map(org.springframework.security.core.authority.SimpleGrantedAuthority::new)
                        .toArray(org.springframework.security.core.GrantedAuthority[]::new));
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("tenantId", TENANT).claim("admin", true));
    }

    private static RequestPostProcessor mustChange() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("tenantId", TENANT)
                .claim("mustChangePassword", true));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private List<MockHttpServletRequestBuilder> allEndpoints() {
        return List.of(
                json(post("/internal/departments"), CREATE_BODY),
                get("/internal/departments"),
                get("/internal/departments/tree"),
                get("/internal/departments/" + ID),
                json(put("/internal/departments/" + ID), UPDATE_BODY),
                json(put("/internal/departments/" + ID + "/status"), STATUS_BODY));
    }

    @Test
    void khongTokenBi401() throws Exception {
        for (MockHttpServletRequestBuilder r : allEndpoints()) {
            mvc.perform(r).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(create, update, changeStatus, get, list);
    }

    @Test
    void thieuKhoaBi403() throws Exception {
        for (MockHttpServletRequestBuilder r : allEndpoints()) {
            mvc.perform(r.with(withKey("OtherController/other"))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(create, update, changeStatus, get, list);
    }

    @Test
    void khoaCuaMethodKhacKhongDuDeGoiMethodNay() throws Exception {
        mvc.perform(json(post("/internal/departments"), CREATE_BODY).with(withKey("DepartmentController/list")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(create);
    }

    @Test
    void tokenMustChangePasswordKhongCoQuyenBi403() throws Exception {
        for (MockHttpServletRequestBuilder r : allEndpoints()) {
            mvc.perform(r.with(mustChange())).andExpect(status().isForbidden());
        }
        verifyNoInteractions(create, update, changeStatus, get, list);
    }

    @Test
    void khongCoTenantTraVe403TenantRequiredKeCaAdmin() throws Exception {
        RequestPostProcessor noTenant = jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("admin", true));
        mvc.perform(get("/internal/departments").with(noTenant))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
        verifyNoInteractions(list);
    }

    @Test
    void coKhoaGoiDuocTungEndpoint() throws Exception {
        when(create.execute(any())).thenReturn(resp());
        when(list.list(null)).thenReturn(List.of(resp()));
        when(list.tree(false)).thenReturn(List.of(new DepartmentNode(ID, "IT", "Phòng CNTT",
                DepartmentStatus.ACTIVE, List.of())));
        when(get.execute(ID)).thenReturn(resp());
        when(update.execute(eq(ID), any())).thenReturn(resp());
        when(changeStatus.execute(ID, DepartmentStatus.INACTIVE)).thenReturn(resp());

        mvc.perform(json(post("/internal/departments"), CREATE_BODY).with(withKey("DepartmentController/create")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/internal/departments/" + ID))
                .andExpect(jsonPath("$.code").value("IT"));
        mvc.perform(get("/internal/departments").with(withKey("DepartmentController/list")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(ID.toString()));
        mvc.perform(get("/internal/departments/tree").with(withKey("DepartmentController/tree")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].code").value("IT"));
        mvc.perform(get("/internal/departments/" + ID).with(withKey("DepartmentController/get")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Phòng CNTT"));
        mvc.perform(json(put("/internal/departments/" + ID), UPDATE_BODY).with(withKey("DepartmentController/update")))
                .andExpect(status().isOk());
        mvc.perform(json(put("/internal/departments/" + ID + "/status"), STATUS_BODY)
                        .with(withKey("DepartmentController/changeStatus")))
                .andExpect(status().isOk());
    }

    @Test
    void adminBoQuaKiemKhoa() throws Exception {
        when(list.list(DepartmentStatus.ACTIVE)).thenReturn(List.of(resp()));
        mvc.perform(get("/internal/departments").param("status", "ACTIVE").with(admin()))
                .andExpect(status().isOk());
        verify(list).list(DepartmentStatus.ACTIVE);
    }

    @Test
    void treeTruyenIncludeInactive() throws Exception {
        when(list.tree(true)).thenReturn(List.of());
        mvc.perform(get("/internal/departments/tree").param("includeInactive", "true").with(admin()))
                .andExpect(status().isOk());
        verify(list).tree(true);
    }

    @Test
    void statusQuerySaiKieuBi400() throws Exception {
        mvc.perform(get("/internal/departments").param("status", "KHONG_CO").with(admin()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(list);
    }

    @Test
    void idKhongPhaiUuidBi400() throws Exception {
        mvc.perform(get("/internal/departments/abc").with(admin())).andExpect(status().isBadRequest());
        verifyNoInteractions(get);
    }

    @Test
    void taoThieuCodeHoacNameBi400() throws Exception {
        mvc.perform(json(post("/internal/departments"), "{\"name\":\"X\"}").with(admin()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(json(post("/internal/departments"), "{\"code\":\"IT\",\"name\":\" \"}").with(admin()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(create);
    }

    @Test
    void taoCodeQuaDaiBi400() throws Exception {
        String body = "{\"code\":\"" + "A".repeat(51) + "\",\"name\":\"X\"}";
        mvc.perform(json(post("/internal/departments"), body).with(admin())).andExpect(status().isBadRequest());
        verifyNoInteractions(create);
    }

    @Test
    void taoCodeDungBienToiDa50QuaValidation() throws Exception {
        when(create.execute(any())).thenReturn(resp());
        String body = "{\"code\":\"" + "A".repeat(50) + "\",\"name\":\"X\"}";
        mvc.perform(json(post("/internal/departments"), body).with(admin())).andExpect(status().isCreated());
    }

    @Test
    void capNhatThieuNameBi400() throws Exception {
        mvc.perform(json(put("/internal/departments/" + ID), "{}").with(admin())).andExpect(status().isBadRequest());
        verifyNoInteractions(update);
    }

    @Test
    void doiTrangThaiThieuHoacSaiGiaTriBi400() throws Exception {
        mvc.perform(json(put("/internal/departments/" + ID + "/status"), "{}").with(admin()))
                .andExpect(status().isBadRequest());
        mvc.perform(json(put("/internal/departments/" + ID + "/status"), "{\"status\":\"DELETED\"}").with(admin()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(changeStatus);
    }

    @Test
    void bodyKhongPhaiJsonBi400() throws Exception {
        mvc.perform(json(post("/internal/departments"), "{khong json").with(admin()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void khongTimThayTraVe404() throws Exception {
        when(get.execute(ID)).thenThrow(new DepartmentNotFoundException(ID));
        when(update.execute(eq(ID), any())).thenThrow(new DepartmentNotFoundException(ID));
        when(changeStatus.execute(eq(ID), any())).thenThrow(new DepartmentNotFoundException(ID));
        mvc.perform(get("/internal/departments/" + ID).with(admin()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DEPARTMENT_NOT_FOUND"));
        mvc.perform(json(put("/internal/departments/" + ID), UPDATE_BODY).with(admin()))
                .andExpect(status().isNotFound());
        mvc.perform(json(put("/internal/departments/" + ID + "/status"), STATUS_BODY).with(admin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void trungMaTraVe409() throws Exception {
        when(create.execute(any())).thenThrow(new DuplicateDepartmentException("Mã phòng ban đã tồn tại: IT"));
        mvc.perform(json(post("/internal/departments"), CREATE_BODY).with(admin()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_DEPARTMENT"));
    }

    @Test
    void vaPhamNghiepVuTraVe422() throws Exception {
        when(update.execute(eq(ID), any())).thenThrow(new InvalidDepartmentException("Vòng lặp cây phòng ban"));
        when(changeStatus.execute(eq(ID), any())).thenThrow(new InvalidDepartmentException("Còn phòng ban con hoạt động"));
        mvc.perform(json(put("/internal/departments/" + ID), UPDATE_BODY).with(admin()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_DEPARTMENT"));
        mvc.perform(json(put("/internal/departments/" + ID + "/status"), STATUS_BODY).with(admin()))
                .andExpect(status().isUnprocessableEntity());
    }
}
