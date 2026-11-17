package lms.system.cms.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Luật cho domain, application và infrastructure của cms; cms độc lập với menu và migration. */
@AnalyzeClasses(packages = "lms.system", importOptions = ImportOption.DoNotIncludeTests.class)
class CmsArchitectureTest {

    @ArchTest
    static final ArchRule domainLaJavaThuan = noClasses()
            .that().resideInAPackage("..system.cms.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta..", "com.fasterxml..", "org.hibernate..", "org.commonmark..",
                    "..cms.application..", "..cms.infrastructure..");

    @ArchTest
    static final ArchRule applicationKhongPhuThuocInfrastructureVaFramework = noClasses()
            .that().resideInAPackage("..system.cms.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..cms.infrastructure..", "jakarta..", "com.fasterxml..", "org.hibernate..", "org.commonmark..",
                    "org.springframework.data..", "org.springframework.web..", "org.springframework.scheduling..",
                    "org.springframework.stereotype..", "org.springframework.context..",
                    "org.springframework.beans..");

    @ArchTest
    static final ArchRule jpaChiOPersistence = noClasses()
            .that().resideInAPackage("..system.cms..").and().resideOutsideOfPackage("..cms.infrastructure.persistence..")
            .should().dependOnClassesThat().resideInAnyPackage("jakarta.persistence..", "org.hibernate..",
                    "org.springframework.data.jpa..");

    @ArchTest
    static final ArchRule commonmarkChiOMarkdown = noClasses()
            .that().resideOutsideOfPackage("..cms.infrastructure.markdown..")
            .should().dependOnClassesThat().resideInAPackage("org.commonmark..")
            .because("render Markdown chỉ nằm sau port MarkdownRendererPort (ADR-0011 mục 2.11)");

    @ArchTest
    static final ArchRule webKhongChamPersistence = noClasses()
            .that().resideInAPackage("..cms.infrastructure.web..")
            .should().dependOnClassesThat().resideInAnyPackage("..cms.infrastructure.persistence..",
                    "..cms.infrastructure.markdown..");

    @ArchTest
    static final ArchRule cmsKhongPhuThuocMenuVaMigration = noClasses()
            .that().resideInAPackage("..system.cms..")
            .should().dependOnClassesThat().resideInAnyPackage("..system.menu..", "..system.migration..");

    @ArchTest
    static final ArchRule menuVaMigrationKhongPhuThuocCms = noClasses()
            .that().resideInAnyPackage("..system.menu..", "..system.migration..")
            .should().dependOnClassesThat().resideInAPackage("..system.cms..");

    @ArchTest
    static final ArchRule cmsKhongDungRedisHayFlyway = noClasses()
            .that().resideInAPackage("..system.cms..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.data.redis..", "io.lettuce..",
                    "org.flywaydb..");
}
