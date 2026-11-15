package lms.identity.tenant.integration;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import lms.identity.config.SecurityConfiguration;
import lms.identity.tenant.application.ListMyTenantsUseCase;
import lms.identity.tenant.application.TenantSummary;
import lms.identity.tenant.infrastructure.web.MeTenantController;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** GET /internal/me/tenants chỉ cần đăng nhập (kể cả token không có tenantId), userId lấy từ token. */
@WebMvcTest(MeTenantController.class)
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class})
class MeTenantSecurityTest {

    @Autowired MockMvc mvc;

    @MockitoBean ListMyTenantsUseCase listMine;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    @Test
    void khongTokenBi401() throws Exception {
        mvc.perform(get("/internal/me/tenants")).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenKhongPermissionKhongTenantIdVanLietKeDuocTenantCuaMinh() throws Exception {
        UUID user = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        when(listMine.execute(user)).thenReturn(List.of(new TenantSummary(tenant, "lib-a", "Thư viện A")));

        mvc.perform(get("/internal/me/tenants").with(jwt().jwt(j -> j.subject(user.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(tenant.toString()))
                .andExpect(jsonPath("$[0].code").value("lib-a"))
                .andExpect(jsonPath("$[0].name").value("Thư viện A"));
    }
}
