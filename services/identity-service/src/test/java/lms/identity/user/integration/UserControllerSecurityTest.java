package lms.identity.user.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
import lms.identity.user.application.ChangeUserStatusUseCase;
import lms.identity.user.application.CreateUserUseCase;
import lms.identity.user.application.GetUserUseCase;
import lms.identity.user.application.ResetUserPasswordUseCase;
import lms.identity.user.application.SearchUsersUseCase;
import lms.identity.user.application.UpdateUserUseCase;
import lms.identity.user.application.UserPageResponse;
import lms.identity.user.application.UserResponse;
import lms.identity.user.domain.DuplicateUserException;
import lms.identity.user.domain.InvalidUserException;
import lms.identity.user.domain.UserNotFoundException;
import lms.identity.user.domain.UserOperationNotAllowedException;
import lms.identity.user.domain.UserStatus;
import lms.identity.user.infrastructure.web.UserController;
import lms.identity.user.infrastructure.web.UserExceptionHandler;
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

/** UserController: khóa UserController/method, /me chỉ cần đăng nhập, admin bỏ qua khóa, validation, lỗi domain. */
@WebMvcTest(UserController.class)
@Import({SecurityConfiguration.class, UserExceptionHandler.class, PermissionAutoConfiguration.class})
@TestPropertySource(properties = {"lms.security.permission.enabled=true",
        "lms.security.permission.require-tenant=true"})
class UserControllerSecurityTest {

    @Autowired MockMvc mvc;

    @MockitoBean CreateUserUseCase create;
    @MockitoBean UpdateUserUseCase update;
    @MockitoBean ChangeUserStatusUseCase changeStatus;
    @MockitoBean GetUserUseCase get;
    @MockitoBean SearchUsersUseCase search;
    @MockitoBean ResetUserPasswordUseCase resetPassword;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    private static final String TENANT = UUID.randomUUID().toString();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID ID = UUID.randomUUID();
    private static final String CREATE_BODY =
            "{\"username\":\"nva\",\"fullName\":\"Nguyễn Văn A\",\"email\":\"a@lib.vn\"}";
    private static final String UPDATE_BODY = "{\"fullName\":\"Nguyễn Văn B\",\"email\":\"b@lib.vn\"}";
    private static final String STATUS_BODY = "{\"status\":\"DISABLED\"}";
    private static final String RESET_BODY = "{\"temporaryPassword\":\"Temp@12345\"}";

    private static UserResponse resp() {
        return new UserResponse(ID, "nva", "Nguyễn Văn A", "a@lib.vn", null, UserStatus.ACTIVE);
    }

    private static RequestPostProcessor withKey(String... keys) {
        return jwt().jwt(j -> j.subject(ACTOR.toString()).claim("tenantId", TENANT).claim("permissions", List.of(keys)))
                .authorities(java.util.Arrays.stream(keys).map(org.springframework.security.core.authority.SimpleGrantedAuthority::new)
                        .toArray(org.springframework.security.core.GrantedAuthority[]::new));
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject(ACTOR.toString()).claim("tenantId", TENANT).claim("admin", true));
    }

    private static RequestPostProcessor mustChange() {
        return jwt().jwt(j -> j.subject(ACTOR.toString()).claim("tenantId", TENANT).claim("mustChangePassword", true));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** Các endpoint cần khóa (không gồm /me). */
    private List<MockHttpServletRequestBuilder> keyedEndpoints() {
        return List.of(
                json(post("/internal/users"), CREATE_BODY),
                get("/internal/users"),
                get("/internal/users/" + ID),
                json(put("/internal/users/" + ID), UPDATE_BODY),
                json(post("/internal/users/" + ID + "/password-reset"), RESET_BODY),
                json(put("/internal/users/" + ID + "/status"), STATUS_BODY));
    }

    private void verifyNoUseCase() {
        verifyNoInteractions(create, update, changeStatus, get, search, resetPassword);
    }

    @Test
    void khongTokenBi401KeCaMe() throws Exception {
        for (MockHttpServletRequestBuilder r : keyedEndpoints()) {
            mvc.perform(r).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/internal/users/me")).andExpect(status().isUnauthorized());
        verifyNoUseCase();
    }

    @Test
    void thieuKhoaBi403() throws Exception {
        for (MockHttpServletRequestBuilder r : keyedEndpoints()) {
            mvc.perform(r.with(withKey("OtherController/other"))).andExpect(status().isForbidden());
        }
        verifyNoUseCase();
    }

    @Test
    void khoaCuaMethodKhacKhongDuDeResetMatKhau() throws Exception {
        mvc.perform(json(post("/internal/users/" + ID + "/password-reset"), RESET_BODY)
                        .with(withKey("UserController/get", "UserController/update")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(resetPassword);
    }

    @Test
    void tokenMustChangePasswordBi403ChoEndpointCanKhoaNhungMeVanDuoc() throws Exception {
        for (MockHttpServletRequestBuilder r : keyedEndpoints()) {
            mvc.perform(r.with(mustChange())).andExpect(status().isForbidden());
        }
        verifyNoUseCase();
        when(get.me(ACTOR)).thenReturn(resp());
        mvc.perform(get("/internal/users/me").with(mustChange())).andExpect(status().isOk());
    }

    @Test
    void meChiCanDangNhapKhongCanKhoaVaLayIdTuToken() throws Exception {
        when(get.me(ACTOR)).thenReturn(resp());
        mvc.perform(get("/internal/users/me").with(withKey("OtherController/other")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("nva"));
        verify(get).me(ACTOR);
    }

    @Test
    void meKhongCoTenantVanDuocViAuthenticatedOnly() throws Exception {
        when(get.me(ACTOR)).thenReturn(resp());
        mvc.perform(get("/internal/users/me").with(jwt().jwt(j -> j.subject(ACTOR.toString()))))
                .andExpect(status().isOk());
    }

    @Test
    void endpointCanKhoaKhongCoTenantTraVe403TenantRequired() throws Exception {
        RequestPostProcessor noTenant = jwt().jwt(j -> j.subject(ACTOR.toString()).claim("admin", true));
        mvc.perform(get("/internal/users").with(noTenant))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
        verifyNoInteractions(search);
    }

    @Test
    void coKhoaGoiDuocTungEndpointVaActorLayTuToken() throws Exception {
        when(create.execute(any())).thenReturn(resp());
        when(search.execute(null, null, null, 0, 20))
                .thenReturn(new UserPageResponse(List.of(resp()), 1, 0, 20));
        when(get.execute(ID)).thenReturn(resp());
        when(update.execute(eq(ACTOR), eq(ID), any())).thenReturn(resp());
        when(changeStatus.execute(ACTOR, ID, UserStatus.DISABLED)).thenReturn(resp());

        mvc.perform(json(post("/internal/users"), CREATE_BODY).with(withKey("UserController/create")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/internal/users/" + ID));
        mvc.perform(get("/internal/users").with(withKey("UserController/search")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(ID.toString()))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/internal/users/" + ID).with(withKey("UserController/get"))).andExpect(status().isOk());
        mvc.perform(json(put("/internal/users/" + ID), UPDATE_BODY).with(withKey("UserController/update")))
                .andExpect(status().isOk());
        mvc.perform(json(post("/internal/users/" + ID + "/password-reset"), RESET_BODY)
                        .with(withKey("UserController/resetPassword")))
                .andExpect(status().isNoContent());
        mvc.perform(json(put("/internal/users/" + ID + "/status"), STATUS_BODY)
                        .with(withKey("UserController/changeStatus")))
                .andExpect(status().isOk());
        verify(resetPassword).execute(ID, "Temp@12345");
    }

    @Test
    void adminBoQuaKiemKhoaVaTruyenThamSoTimKiem() throws Exception {
        UUID dep = UUID.randomUUID();
        when(search.execute("an", dep, UserStatus.ACTIVE, 2, 5))
                .thenReturn(new UserPageResponse(List.of(), 0, 2, 5));
        mvc.perform(get("/internal/users").param("keyword", "an").param("departmentId", dep.toString())
                        .param("status", "ACTIVE").param("page", "2").param("size", "5").with(admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(2)).andExpect(jsonPath("$.size").value(5));
    }

    @Test
    void timKiemThamSoSaiKieuBi400() throws Exception {
        mvc.perform(get("/internal/users").param("status", "LOCKED").with(admin())).andExpect(status().isBadRequest());
        mvc.perform(get("/internal/users").param("page", "abc").with(admin())).andExpect(status().isBadRequest());
        mvc.perform(get("/internal/users").param("departmentId", "x").with(admin())).andExpect(status().isBadRequest());
        verifyNoInteractions(search);
    }

    @Test
    void taoValidationSaiBi400() throws Exception {
        String[] bad = {
                "{\"fullName\":\"A\",\"email\":\"a@lib.vn\"}",
                "{\"username\":\"ab\",\"fullName\":\"A\",\"email\":\"a@lib.vn\"}",
                "{\"username\":\"nva\",\"email\":\"a@lib.vn\"}",
                "{\"username\":\"nva\",\"fullName\":\"A\"}",
                "{\"username\":\"nva\",\"fullName\":\"A\",\"email\":\"khong-phai-email\"}",
                "{\"username\":\"nva\",\"fullName\":\"A\",\"email\":\"a@lib.vn\",\"password\":\"1234567\"}",
                "{\"username\":\"nva\",\"fullName\":\"A\",\"email\":\"a@lib.vn\",\"password\":\"" + "x".repeat(129) + "\"}"
        };
        for (String body : bad) {
            mvc.perform(json(post("/internal/users"), body).with(admin()))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        verifyNoInteractions(create);
    }

    @Test
    void taoBienHopLeQuaValidation() throws Exception {
        when(create.execute(any())).thenReturn(resp());
        String body = "{\"username\":\"abc\",\"fullName\":\"A\",\"email\":\"a@lib.vn\",\"password\":\"12345678\"}";
        mvc.perform(json(post("/internal/users"), body).with(admin())).andExpect(status().isCreated());
    }

    @Test
    void capNhatThieuHoacSaiEmailBi400() throws Exception {
        mvc.perform(json(put("/internal/users/" + ID), "{\"fullName\":\"A\"}").with(admin()))
                .andExpect(status().isBadRequest());
        mvc.perform(json(put("/internal/users/" + ID), "{\"fullName\":\"A\",\"email\":\"sai\"}").with(admin()))
                .andExpect(status().isBadRequest());
        mvc.perform(json(put("/internal/users/" + ID), "{\"email\":\"a@lib.vn\"}").with(admin()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(update);
    }

    @Test
    void resetMatKhauTamNganHoacThieuBi400() throws Exception {
        mvc.perform(json(post("/internal/users/" + ID + "/password-reset"), "{\"temporaryPassword\":\"short\"}")
                .with(admin())).andExpect(status().isBadRequest());
        mvc.perform(json(post("/internal/users/" + ID + "/password-reset"), "{}").with(admin()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(resetPassword);
    }

    @Test
    void doiTrangThaiThieuHoacSaiGiaTriBi400() throws Exception {
        mvc.perform(json(put("/internal/users/" + ID + "/status"), "{}").with(admin()))
                .andExpect(status().isBadRequest());
        mvc.perform(json(put("/internal/users/" + ID + "/status"), "{\"status\":\"BANNED\"}").with(admin()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(changeStatus);
    }

    @Test
    void idKhongPhaiUuidBi400() throws Exception {
        mvc.perform(get("/internal/users/abc").with(admin())).andExpect(status().isBadRequest());
        verifyNoInteractions(get);
    }

    @Test
    void khongTimThayHoacNgoaiTenantTraVe404() throws Exception {
        when(get.execute(ID)).thenThrow(new UserNotFoundException(ID));
        when(update.execute(any(), eq(ID), any())).thenThrow(new UserNotFoundException(ID));
        when(changeStatus.execute(any(), eq(ID), any())).thenThrow(new UserNotFoundException(ID));
        doThrow(new UserNotFoundException(ID)).when(resetPassword).execute(eq(ID), any());
        mvc.perform(get("/internal/users/" + ID).with(admin()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        mvc.perform(json(put("/internal/users/" + ID), UPDATE_BODY).with(admin())).andExpect(status().isNotFound());
        mvc.perform(json(put("/internal/users/" + ID + "/status"), STATUS_BODY).with(admin()))
                .andExpect(status().isNotFound());
        mvc.perform(json(post("/internal/users/" + ID + "/password-reset"), RESET_BODY).with(admin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void trungTenDangNhapTraVe409() throws Exception {
        when(create.execute(any())).thenThrow(new DuplicateUserException("Tên đăng nhập đã tồn tại"));
        mvc.perform(json(post("/internal/users"), CREATE_BODY).with(admin()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_USER"));
    }

    @Test
    void vaPhamNghiepVuTraVe422() throws Exception {
        when(create.execute(any())).thenThrow(new InvalidUserException("Phòng ban không hợp lệ"));
        mvc.perform(json(post("/internal/users"), CREATE_BODY).with(admin()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_USER"));
    }

    @Test
    void thaoTacBiCamTraVe403DuLaAdmin() throws Exception {
        when(changeStatus.execute(any(), eq(ID), any()))
                .thenThrow(new UserOperationNotAllowedException("Không thể tự khóa chính mình"));
        mvc.perform(json(put("/internal/users/" + ID + "/status"), STATUS_BODY).with(admin()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("USER_OPERATION_NOT_ALLOWED"));
    }
}
