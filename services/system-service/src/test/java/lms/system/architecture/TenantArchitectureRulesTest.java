package lms.system.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import lms.tenant.architecture.TenantArchitectureRules;

/** Luật multi-tenant dùng chung (ADR-0006): entity có tenant_id phải @TenantId, không native query chưa rà. */
@AnalyzeClasses(packages = "lms.system", importOptions = ImportOption.DoNotIncludeTests.class)
class TenantArchitectureRulesTest {

    @ArchTest
    static final ArchRule entityTheoTenantPhaiCoTenantId = TenantArchitectureRules.tenantScopedEntitiesMustUseTenantId();

    @ArchTest
    static final ArchRule khongCoNativeQueryChuaRa = TenantArchitectureRules.noUnreviewedNativeQueries();
}
