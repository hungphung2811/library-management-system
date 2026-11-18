package lms.imports.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** Ràng buộc ADR-0024 mục 2.3 và 3.1 cho import-common. */
class ImportArchitectureTest {

    private static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("lms.imports");

    @Test
    void coreIsPlainJavaSoServiceApplicationLayersCanDependOnIt() {
        noClasses().that().resideInAPackage("lms.imports.core..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta..", "javax.servlet..",
                        "org.apache.poi..", "com.fasterxml..", "io.lettuce..", "lms.imports.xlsx..", "lms.imports.redis..",
                        "lms.imports.engine..", "lms.imports.web..", "lms.imports.autoconfigure..")
                .check(MAIN);
    }

    @Test
    void poiOnlyAppearsInsideTheXlsxPackage() {
        noClasses().that().resideOutsideOfPackage("lms.imports.xlsx..")
                .should().dependOnClassesThat().resideInAnyPackage("org.apache.poi..", "org.apache.xmlbeans..")
                .check(MAIN);
    }

    @Test
    void neverUseDomModelsToReadUserFiles() {
        noClasses().should().dependOnClassesThat().haveFullyQualifiedName("org.apache.poi.ss.usermodel.WorkbookFactory")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("org.apache.poi.xssf.usermodel.XSSFWorkbook")
                .orShould().dependOnClassesThat().resideInAPackage("org.apache.poi.hssf..")
                .orShould().dependOnClassesThat().resideInAPackage("org.apache.poi.hwpf..")
                .check(MAIN);
    }

    @Test
    void redisSpecificsStayInRedisPackageAndEngineDoesNotKnowSpringWeb() {
        noClasses().that().resideOutsideOfPackage("lms.imports.redis..").and().resideOutsideOfPackage("lms.imports.autoconfigure..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.data.redis..", "io.lettuce..")
                .check(MAIN);
        noClasses().that().resideInAPackage("lms.imports.engine..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web..", "jakarta.servlet..")
                .check(MAIN);
    }

    @Test
    void noPackageNamedLikeAServiceLayerSoServiceArchitectureRulesDoNotMisfire() {
        classes().that().resideInAnyPackage("lms.imports..").should().resideOutsideOfPackages(
                "..domain..", "..application..", "..infrastructure..").check(MAIN);
        assertThat(MAIN.size()).isGreaterThan(30);
    }
}
