package lms.api.system;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import lms.api.config.SecurityConfiguration;
import lms.api.system.client.SystemClient;
import lms.api.system.dto.MenuDtos.CreateMenuRequest;
import lms.api.system.dto.MenuDtos.MenuAdminNodeView;
import lms.api.system.dto.MenuDtos.MenuNodeView;
import lms.api.system.dto.MenuDtos.UpdateMenuRequest;
import lms.api.system.web.MenuAdminController;
import lms.api.system.web.MenuController;
import lms.api.web.ApiExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;

/** Hợp đồng FE của menu: xác thực, ánh xạ DTO, mã 201/204 và chuyển nguyên lỗi MENU_* của system-service. */
@WebMvcTest({MenuController.class, MenuAdminController.class})
@Import({SecurityConfiguration.class, ApiExceptionHandler.class})
class MenuControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean SystemClient system;
    @MockitoBean JwtDecoder jwtDecoder;

    private static MenuAdminNodeView admin(UUID id) {
        return new MenuAdminNodeView(id, "books", "Sách", "/books", "book", null, 10, true, "BookController/search", 0L,
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static HttpClientErrorException downstream(HttpStatus status, String code) {
        String body = "{\"code\":\"" + code + "\",\"message\":\"x\",\"details\":[]}";
        return HttpClientErrorException.create(status, "err", HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }

    @Test
    void khongTokenBi401() throws Exception {
        mvc.perform(get("/api/menus/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/menus")).andExpect(status().isUnauthorized());
    }

    @Test
    void menuCuaToiTraCayVaDanhSachRongKhiKhongCoMuc() throws Exception {
        MenuNodeView child = new MenuNodeView(UUID.randomUUID(), "loans", "Mượn", "/loans", null, List.of());
        MenuNodeView root = new MenuNodeView(UUID.randomUUID(), "circ", "Lưu thông", null, "swap", List.of(child));
        when(system.myMenu()).thenReturn(List.of(root));
        mvc.perform(get("/api/menus/me").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("circ"))
                .andExpect(jsonPath("$[0].children[0].path").value("/loans"));

        when(system.myMenu()).thenReturn(List.of());
        mvc.perform(get("/api/menus/me").with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void quanTriLietKeGomMucTat() throws Exception {
        UUID id = UUID.randomUUID();
        when(system.listMenus()).thenReturn(List.of(admin(id)));
        mvc.perform(get("/api/admin/menus").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].permissionKey").value("BookController/search"))
                .andExpect(jsonPath("$[0].version").value(0))
                .andExpect(jsonPath("$[0].children").doesNotExist());
    }

    @Test
    void taoTraVe201KemLocation() throws Exception {
        UUID id = UUID.randomUUID();
        when(system.createMenu(any(CreateMenuRequest.class))).thenReturn(admin(id));
        mvc.perform(post("/api/admin/menus").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"books\",\"label\":\"Sách\",\"path\":\"/books\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/admin/menus/" + id));
    }

    @Test
    void taoThieuCodeBi400TaiBienKhongGoiService() throws Exception {
        mvc.perform(post("/api/admin/menus").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Sách\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void taoQuaDoDaiBi400() throws Exception {
        String code = "a".repeat(101);
        mvc.perform(post("/api/admin/menus").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"label\":\"A\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void suaPhaiCoVersion() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(put("/api/admin/menus/" + id).with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Sách\",\"sortOrder\":1,\"enabled\":true}"))
                .andExpect(status().isBadRequest());

        when(system.updateMenu(eq(id), any(UpdateMenuRequest.class))).thenReturn(admin(id));
        mvc.perform(put("/api/admin/menus/" + id).with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Sách\",\"sortOrder\":1,\"enabled\":true,\"version\":0}"))
                .andExpect(status().isOk());
    }

    @Test
    void xoaTraVe204() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(delete("/api/admin/menus/" + id).with(jwt())).andExpect(status().isNoContent());
        verify(system).deleteMenu(id);
    }

    @Test
    void maLoiMenuChuyenNguyen() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(downstream(HttpStatus.CONFLICT, "MENU_HAS_CHILDREN")).when(system).deleteMenu(id);
        mvc.perform(delete("/api/admin/menus/" + id).with(jwt()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MENU_HAS_CHILDREN"));

        when(system.updateMenu(eq(id), any(UpdateMenuRequest.class)))
                .thenThrow(downstream(HttpStatus.NOT_FOUND, "MENU_NOT_FOUND"));
        mvc.perform(put("/api/admin/menus/" + id).with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"S\",\"sortOrder\":1,\"enabled\":true,\"version\":0}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MENU_NOT_FOUND"));

        when(system.createMenu(any(CreateMenuRequest.class)))
                .thenThrow(downstream(HttpStatus.BAD_REQUEST, "MENU_PATH_INVALID"));
        mvc.perform(post("/api/admin/menus").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"a\",\"label\":\"A\",\"path\":\"//evil\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MENU_PATH_INVALID"));
    }
}
