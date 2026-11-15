package lms.support.notification.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "lms.support", importOptions = ImportOption.DoNotIncludeTests.class)
class NotificationArchitectureTest {

    @ArchTest
    static final ArchRule domainKhongPhuThuocFrameworkVaTangNgoai = noClasses()
            .that().resideInAPackage("lms.support.notification.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta..", "com.fasterxml..", "org.hibernate..", "lms.database..",
                    "lms.support.notification.application..", "lms.support.notification.infrastructure..");

    @ArchTest
    static final ArchRule applicationKhongPhuThuocInfrastructureVaDatabaseCommon = noClasses()
            .that().resideInAPackage("lms.support.notification.application..")
            .should().dependOnClassesThat().resideInAnyPackage("lms.support.notification.infrastructure..",
                    "lms.database..");

    @ArchTest
    static final ArchRule khongCoVongLapGiuaCacTangCuaNotification =
            slices().matching("lms.support.notification.(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule nguCanhNotificationKhongPhuThuocNguCanhKhac = noClasses()
            .that().resideInAPackage("lms.support.notification..")
            .should().dependOnClassesThat().resideInAnyPackage("lms.support.audit..", "lms.identity..",
                    "lms.circulation..", "lms.catalog..");
}
