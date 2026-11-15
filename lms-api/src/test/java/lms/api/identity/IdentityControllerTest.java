package lms.api.identity;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import lms.api.config.SecurityConfiguration;
import lms.api.identity.client.AccessClient;
import lms.api.identity.client.AuthClient;
import lms.api.identity.client.DepartmentClient;
import lms.api.identity.client.UserClient;
import lms.api.identity.dto.AccessDtos.RoleView;
import lms.api.identity.dto.AuthDtos.TokenView;
import lms.api.identity.dto.DepartmentDtos.DepartmentNodeView;
import lms.api.identity.dto.UserDtos.SearchUsersQuery;
import lms.api.identity.dto.UserDtos.UserPageView;
import lms.api.identity.dto.UserDtos.UserView;
import lms.api.identity.web.AccessController;
import lms.api.identity.web.AuthController;
import lms.api.identity.web.DepartmentController;
import lms.api.identity.web.ExternalAuthController;
import lms.api.identity.web.UserController;
import lms.api.web.ApiExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

/** Hợp đồng FE của identity: công khai đúng chỗ, 401 ở chỗ còn lại, ánh xạ DTO, chuyển nguyên lỗi 4xx và redirect/cookie. */
@WebMvcTest({AuthController.class, ExternalAuthController.class, AccessController.class, UserController.class,
        DepartmentController.class})
@Import({SecurityConfiguration.class, ApiExceptionHandler.class})
class IdentityControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean AuthClient auth;
    @MockitoBean AccessClient access;
    @MockitoBean UserClient users;
    @MockitoBean DepartmentClient departments;
    @MockitoBean JwtDecoder jwtDecoder;

    static final String LOGIN = "{\"username\":\"alice\",\"password\":\"secret123\"}";
    static final String NO_STORE = "no-store";

    private static HttpClientErrorException error(HttpStatus status, String body) {
        return HttpClientErrorException.create(status, status.name(), HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    @Test
    void cacDuongDanCongKhaiKhongCanToken() throws Exception {
        when(auth.login("alice", "secret123")).thenReturn(new TokenView("a", "r", "Bearer", 900, false));
        when(auth.refresh("r")).thenReturn(new TokenView("a", "r2", "Bearer", 900, false));
        when(auth.externalProviders()).thenReturn(List.of());
        when(auth.externalStart(any(), any())).thenReturn(ResponseEntity.status(302).body(new byte[0]));
        when(auth.externalCallback(any(), any(), any(), any(), any())).thenReturn(ResponseEntity.status(302).body(new byte[0]));
        when(auth.externalExchange("c")).thenReturn(new TokenView("a", "r", "Bearer", 900, false));

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("a"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString(NO_STORE)));
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"r\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString(NO_STORE)));
        mvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"r\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/external/providers")).andExpect(status().isOk());
        mvc.perform(get("/api/auth/external/google/start").param("returnUrl", "http://localhost:3000/"))
                .andExpect(status().isFound());
        mvc.perform(get("/api/auth/external/google/callback").param("code", "x").param("state", "y"))
                .andExpect(status().isFound());
        mvc.perform(post("/api/auth/external/exchange").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"c\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString(NO_STORE)));
    }

    @Test
    void duongDanCongKhaiBoQuaBearerHetHanNhungDuongDanKhacThiKhong() throws Exception {
        when(auth.refresh("r")).thenReturn(new TokenView("a", "r2", "Bearer", 900, false));
        // jwtDecoder là mock: mọi token đều bị từ chối nếu thực sự được dùng
        when(jwtDecoder.decode(any())).thenThrow(new BadJwtException("het han"));
        mvc.perform(post("/api/auth/refresh").header(HttpHeaders.AUTHORIZATION, "Bearer het-han")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"r\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer het-han"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cacDuongDanConLaCanToken() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(post("/api/auth/password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"a\",\"newPassword\":\"12345678\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/credentials").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me/external")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me/external/google/link").param("returnUrl", "x")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/me/reader-profile")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/departments/tree")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/roles")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/permissions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/access/users/" + id)).andExpect(status().isUnauthorized());
    }

    @Test
    void loi4xxCuaServiceChuyenNguyenMaVaNoiDung() throws Exception {
        when(auth.login(any(), any())).thenThrow(error(HttpStatus.LOCKED,
                "{\"code\":\"ACCOUNT_LOCKED\",\"message\":\"Tài khoản bị khóa\",\"details\":[]}"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));

        Mockito.doThrow(error(HttpStatus.UNPROCESSABLE_ENTITY,
                        "{\"code\":\"WRONG_CURRENT_PASSWORD\",\"message\":\"Mật khẩu hiện tại sai\",\"details\":[]}"))
                .when(auth).changePassword(any());
        mvc.perform(post("/api/auth/password").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"sai\",\"newPassword\":\"12345678\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WRONG_CURRENT_PASSWORD"));
    }

    @Test
    void loiPermissionCuaServiceChuyenNguyen403Va5xxThanh502() throws Exception {
        when(access.listRoles()).thenThrow(error(HttpStatus.FORBIDDEN,
                "{\"code\":\"FORBIDDEN\",\"message\":\"Không đủ quyền\",\"details\":[]}"));
        mvc.perform(get("/api/roles").with(jwt())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        when(departments.tree(false)).thenThrow(HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "x",
                HttpHeaders.EMPTY, "bí mật".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        mvc.perform(get("/api/departments/tree").with(jwt())).andExpect(status().isBadGateway())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("bí mật"))));
    }

    @Test
    void dangNhapNgoaiChuyenNguyen302LocationVaSetCookie() throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.LOCATION, "https://idp.example/authorize?state=abc");
        h.add(HttpHeaders.SET_COOKIE, "lms_ext=v1; Path=/api/auth; HttpOnly; SameSite=Lax");
        when(auth.externalStart("google", "http://localhost:3000/")).thenReturn(
                ResponseEntity.status(HttpStatus.FOUND).headers(h).body(new byte[0]));

        mvc.perform(get("/api/auth/external/google/start").param("returnUrl", "http://localhost:3000/"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "https://idp.example/authorize?state=abc"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, "lms_ext=v1; Path=/api/auth; HttpOnly; SameSite=Lax"));
    }

    @Test
    void callbackGuiCookieLmsExtSangServiceVaChuyenNguyenLocationVaCookieXoa() throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.LOCATION, "http://localhost:3000/#externalCode=T1");
        h.add(HttpHeaders.SET_COOKIE, "lms_ext=; Path=/api/auth; Max-Age=0");
        when(auth.externalCallback("google", "c", "s", null, "v1")).thenReturn(
                ResponseEntity.status(HttpStatus.FOUND).headers(h).body(new byte[0]));

        mvc.perform(get("/api/auth/external/google/callback").param("code", "c").param("state", "s")
                        .cookie(new Cookie("lms_ext", "v1")))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "http://localhost:3000/#externalCode=T1"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.startsWith("lms_ext=; Path=/api/auth; Max-Age=0")));
        verify(auth).externalCallback("google", "c", "s", null, "v1");
    }

    @Test
    void callbackLoi400InvalidStateChuyenNguyenKemCookieXoa() throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.SET_COOKIE, "lms_ext=; Path=/api/auth; Max-Age=0");
        h.setContentType(MediaType.APPLICATION_JSON);
        when(auth.externalCallback(any(), any(), any(), any(), any())).thenReturn(ResponseEntity.badRequest().headers(h)
                .body("{\"code\":\"INVALID_STATE\"}".getBytes(StandardCharsets.UTF_8)));
        mvc.perform(get("/api/auth/external/google/callback").param("code", "c").param("state", "s"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"))
                .andExpect(header().exists(HttpHeaders.SET_COOKIE));
    }

    @Test
    void lienKetTaiKhoanNgoaiTraJsonVaCookie() throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.add(HttpHeaders.SET_COOKIE, "lms_ext=v2; Path=/api/auth");
        when(auth.externalLink("google", "http://localhost:3000/")).thenReturn(ResponseEntity.ok().headers(h)
                .body("{\"authorizationUrl\":\"https://idp/x\"}".getBytes(StandardCharsets.UTF_8)));
        mvc.perform(get("/api/auth/me/external/google/link").param("returnUrl", "http://localhost:3000/").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizationUrl").value("https://idp/x"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, "lms_ext=v2; Path=/api/auth"));
    }

    @Test
    void nguoiDungVaTimKiemAnhXaDto() throws Exception {
        UUID id = UUID.randomUUID();
        when(users.search(any())).thenReturn(new UserPageView(
                List.of(new UserView(id, "alice", "Alice", "a@x.vn", null, "ACTIVE")), 1, 0, 20));
        mvc.perform(get("/api/users").param("keyword", "ali").param("size", "20").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].username").value("alice"))
                .andExpect(jsonPath("$.total").value(1));
        ArgumentCaptor<SearchUsersQuery> q = ArgumentCaptor.forClass(SearchUsersQuery.class);
        verify(users).search(q.capture());
        Assertions.assertEquals("ali", q.getValue().keyword());
        Assertions.assertEquals(20, q.getValue().size());

        when(users.create(any())).thenReturn(new UserView(id, "bob", "Bob", "b@x.vn", null, "ACTIVE"));
        mvc.perform(post("/api/users").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob\",\"fullName\":\"Bob\",\"email\":\"b@x.vn\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/users/" + id));
        mvc.perform(post("/api/users").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"b\",\"fullName\":\"\",\"email\":\"sai\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void huyQuyenGiuDauGachCheoTrongKhoa() throws Exception {
        UUID role = UUID.randomUUID();
        when(access.revokePermission(eq(role), eq("BorrowingController/borrow")))
                .thenReturn(new RoleView(role, "r", "d", false, List.of()));
        mvc.perform(delete("/api/roles/" + role + "/permissions/BorrowingController/borrow").with(jwt()))
                .andExpect(status().isOk());
        verify(access).revokePermission(role, "BorrowingController/borrow");
    }

    @Test
    void cayPhongBanTraDanhSachLongNhau() throws Exception {
        UUID id = UUID.randomUUID();
        when(departments.tree(true)).thenReturn(List.of(new DepartmentNodeView(id, "A", "A", "ACTIVE", List.of())));
        mvc.perform(get("/api/departments/tree").param("includeInactive", "true").with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].code").value("A"));
    }
}
