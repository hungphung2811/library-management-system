package lms.circulation.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import lms.tenant.architecture.TenantArchitectureRules;

@AnalyzeClasses(packages = "lms.circulation", importOptions = ImportOption.DoNotIncludeTests.class)
class TenantArchitectureRulesTest {

    @ArchTest
    static final ArchRule entityTheoTenantPhaiCoTenantId = TenantArchitectureRules.tenantScopedEntitiesMustUseTenantId();

    @ArchTest
    static final ArchRule khongCoNativeQueryChuaRa = TenantArchitectureRules.noUnreviewedNativeQueries(
                    // ADR-0018: read model thống kê/báo cáo, mọi truy vấn có tenant_id = :t tường minh cộng RLS
                    "LendingStatsQueryAdapter");
}
