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

    /** ADR-0030: use case chỉ biết cổng; SMTP, Kafka, HTTP, JSON là việc của infrastructure. */
    @ArchTest
    static final ArchRule applicationKhongBietSmtpKafkaHttpVaJson = noClasses()
            .that().resideInAPackage("lms.support.notification.application..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.mail..", "jakarta.mail..",
                    "org.eclipse.angus..", "org.springframework.kafka..", "org.springframework.web..",
                    "org.springframework.http..", "com.fasterxml..");

    /** Cài đặt provider (kênh gửi ra ngoài) chỉ nằm ở infrastructure, đăng ký qua NotificationProviderRegistry. */
    @ArchTest
    static final ArchRule providerChiCaiDatODatInfrastructure = com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes()
            .that().implement(lms.support.notification.application.NotificationProvider.class)
            .should().resideInAPackage("lms.support.notification.infrastructure..");

    /** Mọi controller thông báo nằm ở infrastructure.web và không gọi thẳng cổng lưu trữ hay provider. */
    @ArchTest
    static final ArchRule controllerKhongDungProviderVaCongLuuTru = noClasses()
            .that().resideInAPackage("lms.support.notification.infrastructure.web..")
            .should().dependOnClassesThat().resideInAnyPackage("lms.support.notification.infrastructure.persistence..",
                    "lms.support.notification.infrastructure.email..", "lms.support.notification.infrastructure.client..");

    @ArchTest
    static final ArchRule khongCoVongLapGiuaCacTangCuaNotification =
            slices().matching("lms.support.notification.(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule nguCanhNotificationKhongPhuThuocNguCanhKhac = noClasses()
            .that().resideInAPackage("lms.support.notification..")
            .should().dependOnClassesThat().resideInAnyPackage("lms.support.audit..", "lms.identity..",
                    "lms.circulation..", "lms.catalog..");
}
