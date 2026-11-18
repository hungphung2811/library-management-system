package lms.security.architecture;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import lms.security.architecture.sample.infrastructure.web.SampleControllers;
import lms.security.architecture.sample.other.OutsideWebController;
import org.junit.jupiter.api.Test;

class IntegrationScopeArchitectureRulesTest {

    private static JavaClasses classes(Class<?>... types) {
        return new ClassFileImporter().importClasses(types);
    }

    @Test
    void mauDungQuaCaBonLuat() {
        for (var rule : IntegrationScopeArchitectureRules.all()) {
            assertThatCode(() -> rule.check(classes(SampleControllers.Good.class))).doesNotThrowAnyException();
        }
    }

    @Test
    void khongCoMethodNaoDungAnnotationThiPassRong() {
        for (var rule : IntegrationScopeArchitectureRules.all()) {
            assertThatCode(() -> rule.check(classes(String.class))).doesNotThrowAnyException();
        }
    }

    @Test
    void scopeLaDoDo() {
        assertThatThrownBy(() -> IntegrationScopeArchitectureRules.valueMustBeKnownScope()
                .check(classes(SampleControllers.UnknownScope.class)))
                .isInstanceOf(AssertionError.class).hasMessageContaining("books:delete");
    }

    @Test
    void scopeGhiTrenGetLaDo() {
        assertThatThrownBy(() -> IntegrationScopeArchitectureRules.writeScopeNotOnGet()
                .check(classes(SampleControllers.WriteOnGet.class)))
                .isInstanceOf(AssertionError.class).hasMessageContaining("orders:write");
    }

    @Test
    void methodKhongCoMappingLaDo() {
        assertThatThrownBy(() -> IntegrationScopeArchitectureRules.onlyOnMappingMethodsInWeb()
                .check(classes(SampleControllers.NoMapping.class)))
                .isInstanceOf(AssertionError.class).hasMessageContaining("helper");
    }

    @Test
    void ngoaiPackageInfrastructureWebLaDo() {
        assertThatThrownBy(() -> IntegrationScopeArchitectureRules.onlyOnMappingMethodsInWeb()
                .check(classes(OutsideWebController.class)))
                .isInstanceOf(AssertionError.class).hasMessageContaining("OutsideWebController");
    }

    @Test
    void datTrenClassLaDo() {
        assertThatThrownBy(() -> IntegrationScopeArchitectureRules.notOnClasses()
                .check(classes(SampleControllers.OnClass.class)))
                .isInstanceOf(AssertionError.class).hasMessageContaining("OnClass");
    }
}
