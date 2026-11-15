package lms.system.menu.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import lms.system.config.SecurityConfiguration;
import lms.system.menu.application.CreateMenuUseCase;
import lms.system.menu.application.DeleteMenuUseCase;
import lms.system.menu.application.GetMyMenuUseCase;
import lms.system.menu.application.ListMenusUseCase;
import lms.system.menu.application.UpdateMenuUseCase;
import lms.system.menu.domain.MenuErrorCode;
import lms.system.menu.domain.MenuException;
import lms.system.menu.domain.MenuItem;
import lms.system.menu.domain.MenuNode;
import lms.system.menu.domain.MenuViewer;
import lms.system.menu.infrastructure.web.MenuAdminController;
import lms.system.menu.infrastructure.web.MenuController;
import lms.tenant.TenantAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Xác thực + khóa quyền động + hợp đồng JSON + ánh xạ mã lỗi của API menu. Không cần Docker. Kiểm khóa được bật lại
 * (profile dev tắt) và TenantContext do TenantContextFilter đặt từ claim tenantId.
 */
@WebMvcTest({MenuController.class, MenuAdminController.class})
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class, TenantAutoConfiguration.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true",
        "lms.security.permission.require-tenant=true"})
class MenuSecurityTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    MockMvc mvc;

    @MockitoBean GetMyMenuUseCase getMyMenu;
    @MockitoBean ListMenusUseCase listMenus;
    @MockitoBean CreateMenuUseCase createMenu;
    @MockitoBean UpdateMenuUseCase updateMenu;
    @MockitoBean DeleteMenuUseCase deleteMenu;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    private static RequestPostProcessor user(String... permissions) {
        // Giống PermissionJwtConverter: claim permissions đồng thời là authority (PermissionInterceptor đọc authority)
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("tenantId", TENANT.toString())
                        .claim("permissions", List.of(permissions)))
                .authorities(java.util.Arrays.stream(permissions)
                        .map(p -> (org.springframework.security.core.GrantedAuthority) new org.springframework.security.core.authority.SimpleGrantedAuthority(p))
                        .toList());
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("tenantId", TENANT.toString())
                .claim("admin", true));
    }

    private static RequestPostProcessor platformAdminNoTenant() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("admin", true));
    }

    private static MenuItem item(String code, String path) {
        return new MenuItem(UUID.randomUUID(), TENANT, null, code, "Nhãn " + code, path, "icon", 10, true,
                "Khoa/secret", 3, NOW, NOW);
    }

    private static final String CREATE_BODY = """
            {"code":"books","label":"Sách","path":"/catalog","sortOrder":5}""";

    // ---------- xác thực ----------

    @Test
    void khongTokenBi401() throws Exception {
        mvc.perform(get("/internal/menus/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/menus")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/menus").contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isUnauthorized());
    }

    // ---------- GET /me ----------

    @Test
    void nguoiDungThuongDocMeVaKhongLoPermissionKeyHayEnabled() throws Exception {
        MenuItem group = item("manage", null);
        MenuItem leaf = item("users", "/users");
        when(getMyMenu.execute(eq(TENANT), any())).thenReturn(List.of(new MenuNode(group, List.of(new MenuNode(leaf,
                List.of())))));

        mvc.perform(get("/internal/menus/me").with(user("A/b", "C/d")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("manage"))
                .andExpect(jsonPath("$[0].path").doesNotExist())
                .andExpect(jsonPath("$[0].children[0].code").value("users"))
                .andExpect(jsonPath("$[0].children[0].path").value("/users"))
                .andExpect(jsonPath("$[0].children[0].children").isEmpty())
                .andExpect(jsonPath("$[0].permissionKey").doesNotExist())
                .andExpect(jsonPath("$[0].enabled").doesNotExist())
                .andExpect(jsonPath("$[0].children[0].permissionKey").doesNotExist());

        ArgumentCaptor<MenuViewer> viewer = ArgumentCaptor.forClass(MenuViewer.class);
        verify(getMyMenu).execute(eq(TENANT), viewer.capture());
        assertThat(viewer.getValue().admin()).isFalse();
        assertThat(viewer.getValue().permissions()).containsExactlyInAnyOrder("A/b", "C/d");
    }

    @Test
    void adminDocMeVoiCoAdminTrue() throws Exception {
        when(getMyMenu.execute(eq(TENANT), any())).thenReturn(List.of());

        mvc.perform(get("/internal/menus/me").with(admin())).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());

        ArgumentCaptor<MenuViewer> viewer = ArgumentCaptor.forClass(MenuViewer.class);
        verify(getMyMenu).execute(eq(TENANT), viewer.capture());
        assertThat(viewer.getValue().admin()).isTrue();
    }

    @Test
    void tokenKhongTenantDocMeTraMangRongVaTruyenTenantNull() throws Exception {
        when(getMyMenu.execute(eq(null), any())).thenReturn(List.of());

        mvc.perform(get("/internal/menus/me").with(platformAdminNoTenant()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        verify(getMyMenu).execute(eq(null), any());
    }

    // ---------- API quản trị: khóa quyền động ----------

    @Test
    void nguoiThuongKhongCoKhoaBi403TrenMoiEndpointQuanTri() throws Exception {
        UUID id = UUID.randomUUID();
        String update = """
                {"label":"X","path":"/x","sortOrder":1,"enabled":true,"version":0}""";
        mvc.perform(get("/internal/menus").with(user("Khac/khoa"))).andExpect(status().isForbidden());
        mvc.perform(post("/internal/menus").with(user()).contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isForbidden());
        mvc.perform(put("/internal/menus/" + id).with(user()).contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/internal/menus/" + id).with(user())).andExpect(status().isForbidden());

        verifyNoInteractions(listMenus, createMenu, updateMenu, deleteMenu);
    }

    @Test
    void khoaQuyenDongLaTenControllerSlashTenMethod() throws Exception {
        when(listMenus.execute(TENANT)).thenReturn(List.of());

        mvc.perform(get("/internal/menus").with(user("MenuAdminController/listMenus")))
                .andExpect(status().isOk());
        // Có khóa của method khác thì vẫn bị chặn
        mvc.perform(get("/internal/menus").with(user("MenuAdminController/createMenu")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminTokenKhongCoTenantBi403TenantRequired() throws Exception {
        mvc.perform(get("/internal/menus").with(platformAdminNoTenant()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
    }

    // ---------- API quản trị: hợp đồng ----------

    @Test
    void adminListTraDanhPhangDayDuTruong() throws Exception {
        MenuItem a = item("a", "/a");
        when(listMenus.execute(TENANT)).thenReturn(List.of(a));

        mvc.perform(get("/internal/menus").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(a.id().toString()))
                .andExpect(jsonPath("$[0].code").value("a"))
                .andExpect(jsonPath("$[0].parentId").doesNotExist())
                .andExpect(jsonPath("$[0].sortOrder").value(10))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[0].permissionKey").value("Khoa/secret"))
                .andExpect(jsonPath("$[0].version").value(3))
                .andExpect(jsonPath("$[0].updatedAt").exists());
    }

    @Test
    void taoTra201VaApDungMacDinhSortOrderEnabled() throws Exception {
        MenuItem created = item("books", "/catalog");
        when(createMenu.execute(eq(TENANT), any())).thenReturn(created);

        mvc.perform(post("/internal/menus").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"books\",\"label\":\"Sách\",\"path\":\"/catalog\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("books"));

        ArgumentCaptor<CreateMenuUseCase.Command> cmd = ArgumentCaptor.forClass(CreateMenuUseCase.Command.class);
        verify(createMenu).execute(eq(TENANT), cmd.capture());
        assertThat(cmd.getValue().code()).isEqualTo("books");
        assertThat(cmd.getValue().details().sortOrder()).isZero();
        assertThat(cmd.getValue().details().enabled()).isTrue();
    }

    @Test
    void suaTra200VaTruyenVersionVaIdDuongDan() throws Exception {
        UUID id = UUID.randomUUID();
        when(updateMenu.execute(eq(TENANT), any())).thenReturn(item("a", "/a"));

        mvc.perform(put("/internal/menus/" + id).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Mới\",\"path\":\"/a\",\"sortOrder\":2,\"enabled\":false,\"version\":7}"))
                .andExpect(status().isOk());

        ArgumentCaptor<UpdateMenuUseCase.Command> cmd = ArgumentCaptor.forClass(UpdateMenuUseCase.Command.class);
        verify(updateMenu).execute(eq(TENANT), cmd.capture());
        assertThat(cmd.getValue().id()).isEqualTo(id);
        assertThat(cmd.getValue().version()).isEqualTo(7);
        assertThat(cmd.getValue().details().enabled()).isFalse();
    }

    @Test
    void xoaTra204() throws Exception {
        UUID id = UUID.randomUUID();

        mvc.perform(delete("/internal/menus/" + id).with(admin())).andExpect(status().isNoContent());

        verify(deleteMenu).execute(TENANT, id);
    }

    @Test
    void duLieuSaiTra400VaKhongGoiUseCase() throws Exception {
        mvc.perform(post("/internal/menus").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"\",\"label\":\"X\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        // Thiếu version khi sửa
        mvc.perform(put("/internal/menus/" + UUID.randomUUID()).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"X\",\"sortOrder\":1,\"enabled\":true}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(createMenu, updateMenu);
    }

    // ---------- ánh xạ mã lỗi ----------

    private void expectError(MenuErrorCode code, int httpStatus) throws Exception {
        when(createMenu.execute(eq(TENANT), any())).thenThrow(new MenuException(code, "msg"));
        mvc.perform(post("/internal/menus").with(admin()).contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.code").value(code.name()));
    }

    @Test
    void maLoiNghiepVuAnhXaDungHttpStatus() throws Exception {
        expectError(MenuErrorCode.MENU_NOT_FOUND, 404);
        expectError(MenuErrorCode.MENU_PARENT_NOT_FOUND, 404);
        expectError(MenuErrorCode.MENU_CODE_DUPLICATED, 409);
        expectError(MenuErrorCode.MENU_HAS_CHILDREN, 409);
        expectError(MenuErrorCode.MENU_VERSION_CONFLICT, 409);
        expectError(MenuErrorCode.MENU_CYCLE, 400);
        expectError(MenuErrorCode.MENU_TOO_DEEP, 400);
        expectError(MenuErrorCode.MENU_PATH_INVALID, 400);
        expectError(MenuErrorCode.MENU_PERMISSION_KEY_INVALID, 400);
        expectError(MenuErrorCode.MENU_LIMIT_EXCEEDED, 400);
        expectError(MenuErrorCode.MENU_INVALID, 400);
    }
}
