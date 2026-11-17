package lms.circulation.module;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import lms.circulation.config.SecurityConfiguration;
import lms.circulation.fine.application.CollectFinesUseCase;
import lms.circulation.fine.application.GetReaderFinesUseCase;
import lms.circulation.fine.infrastructure.web.FineController;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** ADR-0009: controller circulation thuộc LENDING (đại diện FineController, endpoint chỉ cần đăng nhập vẫn bị kiểm module). */
@WebMvcTest(FineController.class)
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class ModuleBlockingTest {

    @Autowired MockMvc mvc;

    @MockitoBean GetReaderFinesUseCase getFines;
    @MockitoBean CollectFinesUseCase collectFines;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    private static RequestPostProcessor token(Object modules, boolean admin) {
        return jwt().jwt(j -> {
            j.subject(UUID.randomUUID().toString()).claim("tenantId", UUID.randomUUID().toString());
            if (modules != null) j.claim("modules", modules);
            if (admin) j.claim("admin", true);
        });
    }

    @Test
    void modulesCoLendingDuocQua() throws Exception {
        mvc.perform(get("/internal/fines/mine").with(token(List.of("LENDING"), false)))
                .andExpect(status().isOk());
    }

    @Test
    void modulesChiCoSalesBiChanKeCaAdmin() throws Exception {
        for (boolean admin : new boolean[]{false, true}) {
            mvc.perform(get("/internal/fines/mine").with(token(List.of("SALES"), admin)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("MODULE_NOT_ENABLED"));
        }
    }

    @Test
    void tokenThieuClaimModulesVanQuaNhoCoLegacy() throws Exception {
        mvc.perform(get("/internal/fines/mine").with(token(null, false))).andExpect(status().isOk());
    }
}
