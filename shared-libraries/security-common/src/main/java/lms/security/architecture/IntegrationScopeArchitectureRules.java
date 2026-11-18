package lms.security.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.util.List;
import java.util.Set;
import lms.security.permission.IntegrationScope;

/**
 * Luật ArchUnit dùng chung cho {@link IntegrationScope} (thiết kế SAAS_API_AUTHENTICATION_DESIGN D3): scope là hợp đồng
 * công khai nên danh sách đóng và vị trí đặt phải kiểm được. Service dùng lại bằng {@code @ArchTest} (cần
 * {@code archunit-junit5} ở test scope), giống {@link ModuleArchitectureRules}. Chưa method nào dùng annotation thì
 * luật pass rỗng.
 */
public final class IntegrationScopeArchitectureRules {

    private IntegrationScopeArchitectureRules() {
    }

    /** Tập scope hợp lệ (hợp đồng công khai có phiên bản; thêm scope mới phải sửa ở đây và ghi tài liệu API). */
    public static final Set<String> VALID_SCOPES = Set.of(
            "books:read", "books:write",
            "inventory:read", "inventory:write",
            "orders:read", "orders:write",
            "customers:read", "customers:write");

    private static final String WEB_PACKAGE_MARKER = ".infrastructure.web";
    private static final String WRITE_SUFFIX = ":write";
    private static final String GET_MAPPING = "org.springframework.web.bind.annotation.GetMapping";
    private static final List<String> MAPPINGS = List.of(
            GET_MAPPING,
            "org.springframework.web.bind.annotation.PostMapping",
            "org.springframework.web.bind.annotation.PutMapping",
            "org.springframework.web.bind.annotation.PatchMapping",
            "org.springframework.web.bind.annotation.DeleteMapping",
            "org.springframework.web.bind.annotation.RequestMapping");

    /** Cả bốn luật cần chạy; tiện cho service muốn khai từng {@code @ArchTest}. */
    public static List<ArchRule> all() {
        return List.of(onlyOnMappingMethodsInWeb(), notOnClasses(), valueMustBeKnownScope(), writeScopeNotOnGet());
    }

    /** {@code @IntegrationScope} chỉ đặt trên method {@code @*Mapping} của package {@code infrastructure.web}, không đặt trên class. */
    public static ArchRule onlyOnMappingMethodsInWeb() {
        return ArchRuleDefinition.methods()
                .that().areAnnotatedWith(IntegrationScope.class)
                .should(new ArchCondition<JavaMethod>("là method @*Mapping trong package infrastructure.web") {
                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        boolean mapped = MAPPINGS.stream().anyMatch(method::isAnnotatedWith);
                        boolean web = method.getOwner().getPackageName().contains(WEB_PACKAGE_MARKER);
                        if (!mapped || !web) {
                            events.add(SimpleConditionEvent.violated(method, method.getFullName()
                                    + " dùng @IntegrationScope ngoài method @*Mapping của infrastructure.web"));
                        }
                    }
                })
                .because("scope tích hợp chỉ có nghĩa trên endpoint HTTP (infrastructure.web)")
                .allowEmptyShould(true);
    }

    /** Annotation đặt trên class bị cấm: mỗi method tự khai scope của mình để không vô tình mở cả controller. */
    public static ArchRule notOnClasses() {
        return ArchRuleDefinition.classes()
                .that().areAnnotatedWith(IntegrationScope.class)
                .should(new ArchCondition<JavaClass>("không mang @IntegrationScope trên class") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        events.add(SimpleConditionEvent.violated(item,
                                item.getName() + " đặt @IntegrationScope trên class; đặt trên từng method"));
                    }
                })
                .because("danh sách endpoint mở cho tích hợp phải nhìn thấy từng method")
                .allowEmptyShould(true);
    }

    /** Giá trị phải thuộc {@link #VALID_SCOPES}. */
    public static ArchRule valueMustBeKnownScope() {
        return ArchRuleDefinition.methods()
                .that().areAnnotatedWith(IntegrationScope.class)
                .should(new ArchCondition<JavaMethod>("có scope thuộc tập hợp lệ " + VALID_SCOPES) {
                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        String value = method.getAnnotationOfType(IntegrationScope.class).value();
                        if (!VALID_SCOPES.contains(value)) {
                            events.add(SimpleConditionEvent.violated(method,
                                    method.getFullName() + " dùng scope không hợp lệ '" + value + "'"));
                        }
                    }
                })
                .because("scope là hợp đồng công khai, chỉ dùng các scope đã công bố")
                .allowEmptyShould(true);
    }

    /** Scope {@code :write} không được đặt trên {@code @GetMapping} (GET phải an toàn, không ghi). */
    public static ArchRule writeScopeNotOnGet() {
        return ArchRuleDefinition.methods()
                .that().areAnnotatedWith(IntegrationScope.class)
                .should(new ArchCondition<JavaMethod>("không đặt scope :write trên @GetMapping") {
                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        String value = method.getAnnotationOfType(IntegrationScope.class).value();
                        if (value.endsWith(WRITE_SUFFIX) && method.isAnnotatedWith(GET_MAPPING)) {
                            events.add(SimpleConditionEvent.violated(method,
                                    method.getFullName() + " đặt scope ghi '" + value + "' trên @GetMapping"));
                        }
                    }
                })
                .because("endpoint GET không được yêu cầu quyền ghi")
                .allowEmptyShould(true);
    }
}
