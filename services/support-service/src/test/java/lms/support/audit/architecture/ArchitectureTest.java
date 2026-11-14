package lms.support.audit.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "lms.support", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domainKhongPhuThuocFrameworkVaTangNgoai = noClasses()
            .that().resideInAPackage("lms.support.audit.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta..", "com.fasterxml..", "org.bson..", "lms.database..",
                    "lms.support.audit.application..", "lms.support.audit.infrastructure..");

    @ArchTest
    static final ArchRule applicationKhongPhuThuocInfrastructureVaDatabaseCommon = noClasses()
            .that().resideInAPackage("lms.support.audit.application..")
            .should().dependOnClassesThat().resideInAnyPackage("lms.support.audit.infrastructure..", "lms.database..");

    @ArchTest
    static final ArchRule khongCoVongLapGiuaCacTangCuaAudit =
            slices().matching("lms.support.audit.(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule nguCanhAuditKhongPhuThuocNguCanhKhac = noClasses()
            .that().resideInAPackage("lms.support.audit..")
            .should().dependOnClassesThat().resideInAnyPackage("lms.support.notification..", "lms.identity..",
                    "lms.circulation..", "lms.catalog..");
}
