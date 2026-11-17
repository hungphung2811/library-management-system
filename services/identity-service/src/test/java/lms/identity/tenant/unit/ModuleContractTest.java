package lms.identity.tenant.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.stream.Collectors;
import lms.identity.tenant.domain.ModuleCode;
import lms.security.JwtClaims;
import lms.security.permission.TenantModule;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** Hợp đồng chéo ADR-0009: ModuleCode (identity) = TenantModule (security-common) = CHECK trong V16; tên claim. */
class ModuleContractTest {

    @Test
    void moduleCodeKhopTenantModuleTheoTenVaThuTu() {
        List<String> a = Arrays.stream(ModuleCode.values()).map(Enum::name).toList();
        List<String> b = Arrays.stream(TenantModule.values()).map(Enum::name).toList();
        assertThat(a).isEqualTo(b);
    }

    @Test
    void tenClaimLaModules() {
        assertThat(JwtClaims.MODULES).isEqualTo("modules");
    }

    @Test
    void moiCheckCuaV16KhopEnum() throws Exception {
        String sql = new String(new ClassPathResource("db/migration/identity/V16__tenant_modules.sql").getInputStream()
                .readAllBytes(), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("module IN [(]([^)]*)[)]").matcher(sql);
        int found = 0;
        Set<String> expected = Arrays.stream(TenantModule.values()).map(Enum::name).collect(Collectors.toSet());
        while (m.find()) {
            found++;
            Set<String> inCheck = Arrays.stream(m.group(1).split(",")).map(s -> s.trim().replace("'", ""))
                    .collect(Collectors.toSet());
            assertThat(inCheck).isEqualTo(expected);
        }
        assertThat(found).isGreaterThanOrEqualTo(2); // tenant_modules và role_templates
    }
}
