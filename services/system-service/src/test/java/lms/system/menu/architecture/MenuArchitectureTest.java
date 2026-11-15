package lms.system.menu.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Luật cho domain, application và infrastructure của menu. */
@AnalyzeClasses(packages = "lms.system.menu", importOptions = ImportOption.DoNotIncludeTests.class)
class MenuArchitectureTest {

    @ArchTest
    static final ArchRule domainLaJavaThuan = noClasses()
            .that().resideInAPackage("..menu.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta..", "com.fasterxml..", "org.hibernate..",
                    "..menu.application..", "..menu.infrastructure..");

    @ArchTest
    static final ArchRule applicationKhongPhuThuocInfrastructureVaFramework = noClasses()
            .that().resideInAPackage("..menu.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..menu.infrastructure..", "jakarta..", "com.fasterxml..", "org.hibernate..",
                    "io.lettuce..", "org.springframework.data..");

    @ArchTest
    static final ArchRule menuKhongPhuThuocMigration = noClasses()
            .that().resideInAPackage("..system.menu..")
            .should().dependOnClassesThat().resideInAPackage("..system.migration..");

    @ArchTest
    static final ArchRule redisChiONhomInfrastructureCuaMenu = noClasses()
            .that().resideOutsideOfPackage("..menu.infrastructure..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.data.redis..", "io.lettuce..")
            .because("Redis chỉ nằm ở menu.infrastructure.cache (ADR-0007)");

    @ArchTest
    static final ArchRule redisChiONhomCache = noClasses()
            .that().resideInAPackage("..menu.infrastructure..")
            .and().resideOutsideOfPackage("..menu.infrastructure.cache..")
            .and().resideOutsideOfPackage("..menu.infrastructure.config..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.data.redis..", "io.lettuce..");

    @ArchTest
    static final ArchRule jpaChiOPersistence = noClasses()
            .that().resideOutsideOfPackage("..menu.infrastructure.persistence..")
            .should().dependOnClassesThat().resideInAnyPackage("jakarta.persistence..", "org.hibernate..",
                    "org.springframework.data.jpa..");

    @ArchTest
    static final ArchRule webKhongChamPersistenceVaCache = noClasses()
            .that().resideInAPackage("..menu.infrastructure.web..")
            .should().dependOnClassesThat().resideInAnyPackage("..menu.infrastructure.persistence..",
                    "..menu.infrastructure.cache..");

    @ArchTest
    static final ArchRule flywayChiOMigration = noClasses()
            .that().resideOutsideOfPackage("..system.migration.infrastructure..")
            .should().dependOnClassesThat().resideInAPackage("org.flywaydb..");
}
