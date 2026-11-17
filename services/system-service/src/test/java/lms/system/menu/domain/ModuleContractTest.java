package lms.system.menu.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lms.security.permission.TenantModule;
import lms.system.menu.application.DefaultMenus;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** Hợp đồng chéo ADR-0009: enum TenantModule (security-common) = MenuRules.MODULES = CHECK trong V2 = module của DefaultMenus. */
class ModuleContractTest {

    private static Set<String> enumNames() {
        return Arrays.stream(TenantModule.values()).map(Enum::name).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Test
    void menuRulesModulesKhopEnumTenantModule() {
        assertThat(MenuRules.MODULES).isEqualTo(enumNames());
    }

    @Test
    void checkCuaV2MenusKhopEnumTenantModule() throws Exception {
        String sql = new String(new ClassPathResource("db/migration/system/V2__add_menu_module.sql").getInputStream()
                .readAllBytes(), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("module IN [(]([^)]*)[)]").matcher(sql);
        assertThat(m.find()).isTrue();
        Set<String> inCheck = Arrays.stream(m.group(1).split(",")).map(s -> s.trim().replace("'", ""))
                .collect(Collectors.toSet());
        assertThat(inCheck).isEqualTo(enumNames());
    }

    @Test
    void moduleCuaDefaultMenusThuocEnumVaCoDuMucLoiLendingSales() {
        Set<String> used = DefaultMenus.ENTRIES.stream().map(DefaultMenus.Entry::module)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertThat(used).contains((String) null, "LENDING", "SALES");
        used.remove(null);
        assertThat(enumNames()).containsAll(used);
    }
}
