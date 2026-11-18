package lms.security.architecture;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import lms.security.architecture.sample.infrastructure.web.TenantDataSamples;
import lms.security.architecture.sample.other.OutsideTenantDataController;
import org.junit.jupiter.api.Test;

class TenantDataArchitectureRulesTest {

    private static JavaClasses classes(Class<?>... types) {
        return new ClassFileImporter().importClasses(types);
    }

    @Test
    void mauDungQuaLuat() {
        for (var rule : TenantDataArchitectureRules.all()) {
            assertThatCode(() -> rule.check(classes(TenantDataSamples.Good.class))).doesNotThrowAnyException();
        }
    }

    @Test
    void khongCoMethodNaoDungAnnotationThiPassRong() {
        for (var rule : TenantDataArchitectureRules.all()) {
            assertThatCode(() -> rule.check(classes(String.class))).doesNotThrowAnyException();
        }
    }

    @Test
    void methodKhongCoMappingLaDo() {
        assertThatThrownBy(() -> TenantDataArchitectureRules.onlyOnMappingMethodsInWeb()
                .check(classes(TenantDataSamples.NoMapping.class)))
                .isInstanceOf(AssertionError.class).hasMessageContaining("helper");
    }

    @Test
    void ngoaiPackageInfrastructureWebLaDo() {
        assertThatThrownBy(() -> TenantDataArchitectureRules.onlyOnMappingMethodsInWeb()
                .check(classes(OutsideTenantDataController.class)))
                .isInstanceOf(AssertionError.class).hasMessageContaining("OutsideTenantDataController");
    }
}
