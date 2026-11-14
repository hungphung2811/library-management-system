package lms.migration.integration;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import lms.migration.application.GetMigrationStatusUseCase;
import lms.migration.application.RunMigrationsUseCase;
import lms.migration.config.SecurityConfiguration;
import lms.migration.domain.MigrationScript;
import lms.migration.domain.MigrationState;
import lms.migration.domain.RunResult;
import lms.migration.domain.TargetMigrationStatus;
import lms.migration.domain.UnknownTargetException;
import lms.migration.infrastructure.web.MigrationController;
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

/** Kiểm chứng xác thực + phân quyền động + hình dạng response. Không cần Docker. */
@WebMvcTest(MigrationController.class)
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
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
        mvc.perform(get("/api/migrations")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/migrations/run")).andExpect(status().isUnauthorized());
    }

    @Test
    void thieuPermissionBi403() throws Exception {
        mvc.perform(get("/api/migrations").with(jwt()
                        .jwt(j -> j.claim("permissions", List.of("MigrationController/runAll")))
                        .authorities(new SimpleGrantedAuthority("MigrationController/runAll"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void duPermissionXemTrangThai() throws Exception {
        when(getStatus.all()).thenReturn(List.of(catalogStatus()));

        mvc.perform(get("/api/migrations").with(jwt()
                        .jwt(j -> j.claim("permissions", List.of("MigrationController/statusAll")))
                        .authorities(new SimpleGrantedAuthority("MigrationController/statusAll"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(1))
                .andExpect(jsonPath("$.pending").value(1))
                .andExpect(jsonPath("$.targets[0].target").value("catalog"))
                .andExpect(jsonPath("$.targets[0].scripts[0].state").value("APPLIED"))
                .andExpect(jsonPath("$.targets[0].scripts[0].executionTimeMs").value(12))
                .andExpect(jsonPath("$.targets[0].scripts[1].state").value("PENDING"));
    }

    @Test
    void chayTargetTraSoScriptDaChay() throws Exception {
        when(runMigrations.runTarget("catalog")).thenReturn(new RunResult("catalog", 1, "2"));

        mvc.perform(post("/api/migrations/catalog/run").with(jwt()
                        .jwt(j -> j.claim("permissions", List.of("MigrationController/runTarget")))
                        .authorities(new SimpleGrantedAuthority("MigrationController/runTarget"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executed").value(1));
    }

    @Test
    void targetLaBi404() throws Exception {
        when(getStatus.byTarget("zzz")).thenThrow(new UnknownTargetException("zzz"));

        mvc.perform(get("/api/migrations/zzz").with(jwt()
                        .jwt(j -> j.claim("permissions", List.of("MigrationController/statusOf")))
                        .authorities(new SimpleGrantedAuthority("MigrationController/statusOf"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MIGRATION_TARGET_NOT_FOUND"));
    }
}
