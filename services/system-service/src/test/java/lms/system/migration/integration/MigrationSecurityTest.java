package lms.system.migration.integration;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import lms.system.migration.application.GetMigrationStatusUseCase;
import lms.system.migration.application.RunMigrationsUseCase;
import lms.system.config.SecurityConfiguration;
import lms.system.migration.domain.MigrationScript;
import lms.system.migration.domain.MigrationState;
import lms.system.migration.domain.RunResult;
import lms.system.migration.domain.TargetMigrationStatus;
import lms.system.migration.domain.UnknownTargetException;
import lms.system.migration.infrastructure.web.MigrationController;
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

/** Kiểm chứng xác thực + @PlatformAdminOnly (ADR-0007) + hình dạng response. Không cần Docker. */
@WebMvcTest(MigrationController.class)
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true",
        "lms.security.permission.require-tenant=true"})
class MigrationSecurityTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean GetMigrationStatusUseCase getStatus;
    @MockitoBean RunMigrationsUseCase runMigrations;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    private static TargetMigrationStatus catalogStatus() {
        return new TargetMigrationStatus("catalog", List.of(
                new MigrationScript("1", "create catalog tables", "V1__create_catalog_tables.sql", "SQL", 123,
                        MigrationState.APPLIED, null, "catalog", 12),
                new MigrationScript("2", "next", "V2__next.sql", "SQL", null, MigrationState.PENDING, null, null,
                        null)));
    }

    @Test
    void khongTokenBi401() throws Exception {
        mvc.perform(get("/internal/migrations")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/migrations/run")).andExpect(status().isUnauthorized());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor platformAdmin() {
        // Quản trị nền tảng: không có tenantId, không có permissions
        return jwt().jwt(j -> j.claim("platformAdmin", true));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor tenantAdmin() {
        return jwt().jwt(j -> j.claim("admin", true).claim("tenantId", "00000000-0000-0000-0000-0000000000d1"));
    }

    @Test
    void tenantAdminBi403() throws Exception {
        mvc.perform(get("/internal/migrations").with(tenantAdmin())).andExpect(status().isForbidden());
        mvc.perform(post("/internal/migrations/run").with(tenantAdmin())).andExpect(status().isForbidden());
        mvc.perform(post("/internal/migrations/catalog/run").with(tenantAdmin())).andExpect(status().isForbidden());
    }

    @Test
    void nguoiCoKhoaMigrationControllerCuVanBi403() throws Exception {
        mvc.perform(get("/internal/migrations").with(jwt()
                        .jwt(j -> j.claim("permissions", List.of("MigrationController/statusAll"))
                                .claim("tenantId", "00000000-0000-0000-0000-0000000000d1"))
                        .authorities(new SimpleGrantedAuthority("MigrationController/statusAll"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void platformAdminXemTrangThaiDuocDuTokenKhongCoTenantId() throws Exception {
        when(getStatus.all()).thenReturn(List.of(catalogStatus()));

        mvc.perform(get("/internal/migrations").with(platformAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(1))
                .andExpect(jsonPath("$.pending").value(1))
                .andExpect(jsonPath("$.targets[0].target").value("catalog"))
                .andExpect(jsonPath("$.targets[0].scripts[0].state").value("APPLIED"))
                .andExpect(jsonPath("$.targets[0].scripts[0].executionTimeMs").value(12))
                .andExpect(jsonPath("$.targets[0].scripts[1].state").value("PENDING"));
    }

    @Test
    void platformAdminChayTargetTraSoScriptDaChay() throws Exception {
        when(runMigrations.runTarget("catalog")).thenReturn(new RunResult("catalog", 1, "2"));

        mvc.perform(post("/internal/migrations/catalog/run").with(platformAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executed").value(1));
    }

    @Test
    void targetLaBi404() throws Exception {
        when(getStatus.byTarget("zzz")).thenThrow(new UnknownTargetException("zzz"));

        mvc.perform(get("/internal/migrations/zzz").with(platformAdmin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MIGRATION_TARGET_NOT_FOUND"));
    }
}
