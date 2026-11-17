package lms.security.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantModule;

/**
 * Luật ArchUnit dùng chung cho ADR-0009: controller của một module phải khai báo {@link RequiresModule} đúng module,
 * quên thì test đỏ. Cần {@code archunit-junit5} ở test scope của service gọi.
 */
public final class ModuleArchitectureRules {

    private ModuleArchitectureRules() {
    }

    /** Mọi {@code @RestController} trong các package khớp (mặc định cả lớp được quét) phải có {@code @RequiresModule(module)} trên class. */
    public static ArchRule controllersMustRequireModule(TenantModule module) {
        return ArchRuleDefinition.classes()
                .that().areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                .should(requireModuleOnClass(module))
                .because("controller của module " + module + " phải có @RequiresModule(" + module + ") (ADR-0009)");
    }

    /** Như trên nhưng chỉ cho các controller có tên đơn giản nằm trong danh sách (ví dụ catalog: BookCopyController). */
    public static ArchRule namedControllersMustRequireModule(TenantModule module, String... simpleClassNames) {
        java.util.Set<String> names = java.util.Set.of(simpleClassNames);
        return ArchRuleDefinition.classes()
                .that(new com.tngtech.archunit.base.DescribedPredicate<JavaClass>("có tên trong " + names) {
                    @Override
                    public boolean test(JavaClass input) {
                        return names.contains(input.getSimpleName());
                    }
                })
                .should(requireModuleOnClass(module))
                .because("controller " + names + " phải có @RequiresModule(" + module + ") (ADR-0009)");
    }

    private static ArchCondition<JavaClass> requireModuleOnClass(TenantModule module) {
        return new ArchCondition<>("được đánh dấu @RequiresModule(" + module + ")") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                boolean ok = item.tryGetAnnotationOfType(RequiresModule.class)
                        .map(a -> a.value() == module)
                        .orElse(false);
                if (!ok) {
                    events.add(SimpleConditionEvent.violated(item,
                            item.getName() + " thiếu @RequiresModule(" + module + ")"));
                }
            }
        };
    }
}
