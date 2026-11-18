package lms.tenant.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import java.util.Optional;
import java.util.Set;
import org.hibernate.annotations.TenantId;

/**
 * Luật ArchUnit dùng chung cho service có dữ liệu theo tenant (multi-tenant, ADR-0006).
 * Mỗi service gọi các luật này trên các lớp production (không gồm test).
 */
public final class TenantArchitectureRules {

    /**
     * Bảng KHÔNG gắn @TenantId có chủ đích: bảng toàn cục hoặc phải đọc được trước khi biết tenant
     * (đăng nhập, làm mới token, danh sách tenant).
     */
    public static final Set<String> TENANT_EXEMPT_TABLES = Set.of(
            "credentials", "users", "external_logins", "permissions", "tenants", "organizations",
            "tenant_memberships", "refresh_tokens", "known_tenants", "tenant_modules",
            // ADR-0012: hợp đồng thuê dịch vụ cấp nền tảng (bảng toàn cục, lọc tenant tường minh trong use case)
            "tenant_contracts", "tenant_contract_modules", "tenant_contract_payments", "tenant_contract_history",
            "tenant_contract_reminders",
            // ADR-0013: origin storefront theo tenant (bảng toàn cục do platform admin ghi, lọc tenant tường minh)
            "tenant_storefront_origins", "tenant_storefront_origin_history",
            // SaaS API Authentication D5: Application/credential/scope/lịch sử là bảng toàn cục có chủ đích (token endpoint và
            // cấp token theo site key tra client_id khi CHƯA biết tenant, nên không RLS). Chống IDOR bằng lọc
            // (tenant_id, id) tường minh ở use case và test hai tenant. application_scopes không có cột tenant_id,
            // giữ trong danh sách như sổ đăng ký bảng toàn cục.
            "applications", "application_credentials", "application_scopes", "application_history",
            // ADR-0011: nội dung và yêu cầu liên hệ cấp nền tảng của system-service (không thuộc tenant nào)
            "cms_pages", "cms_contact_requests",
            // ADR-0030 D8: danh sách tenant đã từng có thông báo của support-service (chỉ id tenant, không chứa dữ liệu tenant;
            // dispatcher lặp qua đây rồi chạy từng tenant trong TenantContext.runAs, giống sales_tenants/known_tenants)
            "notification_tenants");

    private static final String SPRING_QUERY = "org.springframework.data.jpa.repository.Query";

    private TenantArchitectureRules() {
    }

    /** Entity {@code *JpaEntity} có cột tenant_id (trừ bảng ngoại lệ) bắt buộc có trường @TenantId. */
    public static ArchRule tenantScopedEntitiesMustUseTenantId() {
        return ArchRuleDefinition.classes()
                .that().haveSimpleNameEndingWith("JpaEntity")
                .and(hasTenantIdColumn())
                .and(notExemptTable())
                .should(haveFieldAnnotatedWithTenantId())
                .because("entity có cột tenant_id phải dùng @TenantId (trừ bảng ngoại lệ: " + TENANT_EXEMPT_TABLES + ")");
    }

    /**
     * Không dùng {@code @Query(nativeQuery = true)} và {@code createNativeQuery} ngoài các lớp đã rà
     * (query native không đi qua bộ lọc @TenantId của Hibernate, chỉ còn RLS bảo vệ).
     */
    public static ArchRule noUnreviewedNativeQueries(String... reviewedSimpleClassNames) {
        Set<String> reviewed = Set.of(reviewedSimpleClassNames);
        return ArchRuleDefinition.noClasses()
                .that(new DescribedPredicate<JavaClass>("không nằm trong danh sách đã rà " + reviewed) {
                    @Override
                    public boolean test(JavaClass input) {
                        return !reviewed.contains(input.getSimpleName());
                    }
                })
                .should(useNativeQueries())
                .because("query native bỏ qua @TenantId; chỉ lớp đã rà " + reviewed + " được dùng");
    }

    /** {@code @CrossTenantReadable} (đọc xuyên tenant, ADR-0008) chỉ được đặt trên method {@code @GetMapping}. */
    public static ArchRule crossTenantReadableOnlyOnGetMapping() {
        return ArchRuleDefinition.methods()
                .that().areAnnotatedWith(lms.security.permission.CrossTenantReadable.class)
                .should().beAnnotatedWith("org.springframework.web.bind.annotation.GetMapping")
                .allowEmptyShould(true)
                .because("@CrossTenantReadable chỉ cho phép đọc, nên chỉ đi với @GetMapping");
    }

    private static DescribedPredicate<JavaClass> hasTenantIdColumn() {
        return new DescribedPredicate<JavaClass>("có cột tenant_id") {
            @Override
            public boolean test(JavaClass input) {
                return input.getFields().stream().anyMatch(TenantArchitectureRules::isTenantIdColumn);
            }
        };
    }

    private static boolean isTenantIdColumn(JavaField field) {
        if (field.getName().equals("tenantId")) {
            return true;
        }
        return field.tryGetAnnotationOfType(Column.class)
                .map(c -> "tenant_id".equals(c.name()))
                .orElse(false);
    }

    private static DescribedPredicate<JavaClass> notExemptTable() {
        return new DescribedPredicate<JavaClass>("không thuộc bảng ngoại lệ tenant") {
            @Override
            public boolean test(JavaClass input) {
                Optional<Table> table = input.tryGetAnnotationOfType(Table.class);
                return table.map(t -> !TENANT_EXEMPT_TABLES.contains(t.name())).orElse(true);
            }
        };
    }

    private static ArchCondition<JavaClass> haveFieldAnnotatedWithTenantId() {
        return new ArchCondition<JavaClass>("có trường @TenantId") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                boolean ok = item.getFields().stream().anyMatch(f -> f.isAnnotatedWith(TenantId.class));
                if (!ok) {
                    events.add(SimpleConditionEvent.violated(item, item.getName()
                            + " có cột tenant_id nhưng thiếu @TenantId (thêm @TenantId hoặc đưa bảng vào danh sách ngoại lệ)"));
                }
            }
        };
    }

    private static ArchCondition<JavaClass> useNativeQueries() {
        return new ArchCondition<JavaClass>("dùng native query") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (JavaMethod method : item.getMethods()) {
                    for (JavaAnnotation<?> annotation : method.getAnnotations()) {
                        if (annotation.getRawType().getName().equals(SPRING_QUERY)
                                && Boolean.TRUE.equals(annotation.get("nativeQuery").orElse(false))) {
                            events.add(SimpleConditionEvent.violated(method,
                                    method.getFullName() + " dùng @Query(nativeQuery = true) chưa được rà"));
                        }
                    }
                }
                for (JavaMethodCall call : item.getMethodCallsFromSelf()) {
                    if (call.getName().equals("createNativeQuery")) {
                        events.add(SimpleConditionEvent.violated(call,
                                call.getDescription() + " gọi createNativeQuery chưa được rà"));
                    }
                }
            }
        };
    }
}
