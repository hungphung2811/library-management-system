package lms.support.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import lms.tenant.architecture.TenantArchitectureRules;

@AnalyzeClasses(packages = "lms.support", importOptions = ImportOption.DoNotIncludeTests.class)
class TenantArchitectureRulesTest {

    @ArchTest
    static final ArchRule entityTheoTenantPhaiCoTenantId = TenantArchitectureRules.tenantScopedEntitiesMustUseTenantId();

    @ArchTest
    static final ArchRule khongCoNativeQueryChuaRa = TenantArchitectureRules.noUnreviewedNativeQueries(
            "SpringDataAuditLogRepository", "SpringDataNotificationRepository",
            // ADR-0030: chèn "nếu chưa có" theo khóa khử trùng (RLS WITH CHECK chặn khác tenant) và danh sách tenant không RLS
            "SpringDataDeliveryRepository", "SpringDataTenantRegistryRepository");
}
