package lms.tenant.architecture;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import lms.security.permission.CrossTenantReadable;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

class CrossTenantReadableRuleTest {

    static class GoodController {
        @CrossTenantReadable
        @GetMapping("/a")
        public String list() { return ""; }
    }

    static class BadController {
        @CrossTenantReadable
        @PostMapping("/b")
        public String create() { return ""; }
    }

    static class UnannotatedController {
        @PostMapping("/c")
        public String create() { return ""; }
    }

    private static JavaClasses classes(Class<?>... types) {
        return new ClassFileImporter().importClasses(types);
    }

    @Test
    void getMappingVoiAnnotationHopLe() {
        assertThatCode(() -> TenantArchitectureRules.crossTenantReadableOnlyOnGetMapping()
                .check(classes(GoodController.class, UnannotatedController.class))).doesNotThrowAnyException();
    }

    @Test
    void annotationTrenPostMappingViPham() {
        assertThatThrownBy(() -> TenantArchitectureRules.crossTenantReadableOnlyOnGetMapping()
                .check(classes(BadController.class)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("BadController");
    }

    @Test
    void khongCoMethodNaoCoAnnotationThiLuatKhongLoi() {
        assertThatCode(() -> TenantArchitectureRules.crossTenantReadableOnlyOnGetMapping()
                .check(classes(UnannotatedController.class))).doesNotThrowAnyException();
    }
}
