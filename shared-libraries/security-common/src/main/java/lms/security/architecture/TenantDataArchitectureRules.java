package lms.security.architecture;

import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.util.List;
import lms.security.permission.TenantDataAccess;

/**
 * Luật ArchUnit dùng chung cho {@link TenantDataAccess} (ADR-0031 D1): endpoint xuất/xóa dữ liệu tenant chỉ nằm ở
 * method {@code @*Mapping} của package {@code infrastructure.web}. Service dùng lại bằng {@code @ArchTest} (cần
 * {@code archunit-junit5} ở test scope), giống {@link IntegrationScopeArchitectureRules}. Chưa method nào dùng
 * annotation thì luật pass rỗng. (Annotation chỉ có {@code @Target(METHOD)} nên không thể đặt trên class.)
 */
public final class TenantDataArchitectureRules {

    private TenantDataArchitectureRules() {
    }

    private static final String WEB_PACKAGE_MARKER = ".infrastructure.web";
    private static final List<String> MAPPINGS = List.of(
            "org.springframework.web.bind.annotation.GetMapping",
            "org.springframework.web.bind.annotation.PostMapping",
            "org.springframework.web.bind.annotation.PutMapping",
            "org.springframework.web.bind.annotation.PatchMapping",
            "org.springframework.web.bind.annotation.DeleteMapping",
            "org.springframework.web.bind.annotation.RequestMapping");

    /** Các luật cần chạy; tiện cho service muốn khai từng {@code @ArchTest}. */
    public static List<ArchRule> all() {
        return List.of(onlyOnMappingMethodsInWeb());
    }

    /** {@code @TenantDataAccess} chỉ đặt trên method {@code @*Mapping} của package {@code infrastructure.web}. */
    public static ArchRule onlyOnMappingMethodsInWeb() {
        return ArchRuleDefinition.methods()
                .that().areAnnotatedWith(TenantDataAccess.class)
                .should(new ArchCondition<JavaMethod>("là method @*Mapping trong package infrastructure.web") {
                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        boolean mapped = MAPPINGS.stream().anyMatch(method::isAnnotatedWith);
                        boolean web = method.getOwner().getPackageName().contains(WEB_PACKAGE_MARKER);
                        if (!mapped || !web) {
                            events.add(SimpleConditionEvent.violated(method, method.getFullName()
                                    + " dùng @TenantDataAccess ngoài method @*Mapping của infrastructure.web"));
                        }
                    }
                })
                .because("endpoint xuất/xóa dữ liệu tenant chỉ có nghĩa trên HTTP (infrastructure.web)")
                .allowEmptyShould(true);
    }
}
