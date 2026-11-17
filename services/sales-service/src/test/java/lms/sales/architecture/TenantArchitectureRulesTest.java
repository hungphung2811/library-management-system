package lms.sales.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import lms.tenant.architecture.TenantArchitectureRules;

/**
 * Mọi entity JPA của sales_db (bảng có tenant_id + RLS, ADR-0010) phải có @TenantId; luật không cho phép rỗng. Bảng
 * sales_tenants (pha 2, B2.5, không RLS) phải thêm vào TENANT_EXEMPT_TABLES khi tạo. Bulk update JPQL dùng @Modifying, không native query.
 */
@AnalyzeClasses(packages = "lms.sales", importOptions = ImportOption.DoNotIncludeTests.class)
class TenantArchitectureRulesTest {

    @ArchTest
    static final ArchRule entityTheoTenantPhaiCoTenantId =
            TenantArchitectureRules.tenantScopedEntitiesMustUseTenantId();

    @ArchTest
    static final ArchRule khongCoNativeQueryChuaRa =
            TenantArchitectureRules.noUnreviewedNativeQueries().allowEmptyShould(true);
}
