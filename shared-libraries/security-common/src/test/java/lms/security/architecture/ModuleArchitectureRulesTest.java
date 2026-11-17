package lms.security.architecture;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantModule;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

class ModuleArchitectureRulesTest {

    @RestController
    @RequiresModule(TenantModule.SALES)
    static class Marked {
    }

    @RestController
    static class Forgotten {
    }

    @RestController
    @RequiresModule(TenantModule.LENDING)
    static class WrongModule {
    }

    private static JavaClasses classes(Class<?>... types) {
        return new ClassFileImporter().importClasses(types);
    }

    @Test
    void controllerDungModuleThiQua() {
        assertThatCode(() -> ModuleArchitectureRules.controllersMustRequireModule(TenantModule.SALES)
                .check(classes(Marked.class))).doesNotThrowAnyException();
    }

    @Test
    void controllerQuenAnnotationLamLuatDo() {
        assertThatThrownBy(() -> ModuleArchitectureRules.controllersMustRequireModule(TenantModule.SALES)
                .check(classes(Marked.class, Forgotten.class)))
                .isInstanceOf(AssertionError.class).hasMessageContaining("Forgotten");
    }

    @Test
    void controllerSaiModuleLamLuatDo() {
        assertThatThrownBy(() -> ModuleArchitectureRules.controllersMustRequireModule(TenantModule.SALES)
                .check(classes(WrongModule.class)))
                .isInstanceOf(AssertionError.class).hasMessageContaining("WrongModule");
    }

    @Test
    void luatTheoTenChiKiemControllerDuocNeu() {
        assertThatCode(() -> ModuleArchitectureRules.namedControllersMustRequireModule(TenantModule.SALES, "Marked")
                .check(classes(Marked.class, Forgotten.class))).doesNotThrowAnyException();
        assertThatThrownBy(() -> ModuleArchitectureRules.namedControllersMustRequireModule(TenantModule.SALES, "Forgotten")
                .check(classes(Marked.class, Forgotten.class))).isInstanceOf(AssertionError.class);
    }
}
