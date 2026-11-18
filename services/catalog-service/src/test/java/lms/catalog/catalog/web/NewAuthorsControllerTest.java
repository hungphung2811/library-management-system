package lms.catalog.catalog.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import lms.catalog.catalog.application.ListNewAuthorsUseCase;
import lms.catalog.catalog.infrastructure.web.NewAuthorsController;
import lms.catalog.config.SecurityConfiguration;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** WebMvc cho NewAuthorsController: token dịch vụ storefront vào được, khóa NewAuthorsController/list, bind query. */
@WebMvcTest(NewAuthorsController.class)
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class NewAuthorsControllerTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final String URL = "/internal/books/new-authors";

    @Autowired MockMvc mvc;

    @MockitoBean ListNewAuthorsUseCase listNewAuthors;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    private static RequestPostProcessor svcToken() {
        return jwt().jwt(j -> j.claim("svc", "customer-api").claim("tenantId", TENANT.toString())
                .claim("modules", List.of("SALES")).claim("permissions", List.of()));
    }

    private static RequestPostProcessor withKey(String key) {
        return jwt().jwt(j -> j.claim("tenantId", TENANT.toString()).claim("permissions", List.of(key)))
                .authorities(new SimpleGrantedAuthority(key));
    }

    private static RequestPostProcessor noPermission() {
        return jwt().jwt(j -> j.claim("tenantId", TENANT.toString()));
    }

    @Test
    void tokenDichVuStorefrontGoiDuocVaTraMangChuoi() throws Exception {
        when(listNewAuthors.execute(null, null)).thenReturn(List.of("Robert Martin", "Nguyễn Nhật Ánh"));
        mvc.perform(get(URL).with(svcToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("Robert Martin"))
                .andExpect(jsonPath("$[1]").value("Nguyễn Nhật Ánh"));
    }

    @Test
    void bindDaysVaLimitTuQuery() throws Exception {
        when(listNewAuthors.execute(30, 5)).thenReturn(List.of("A"));
        mvc.perform(get(URL + "?days=30&limit=5").with(svcToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("A"));
        verify(listNewAuthors).execute(30, 5);
    }

    @Test
    void tokenNguoiDungCanKhoaNewAuthorsControllerList() throws Exception {
        when(listNewAuthors.execute(null, null)).thenReturn(List.of());
        mvc.perform(get(URL).with(withKey("NewAuthorsController/list"))).andExpect(status().isOk());
        mvc.perform(get(URL).with(withKey("TitleController/lookup"))).andExpect(status().isForbidden());
        mvc.perform(get(URL).with(noPermission())).andExpect(status().isForbidden());
    }

    @Test
    void khongTokenBi401() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        verifyNoInteractions(listNewAuthors);
    }
}
