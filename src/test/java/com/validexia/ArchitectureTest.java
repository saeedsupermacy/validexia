package com.validexia;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Layering rules (M02-1 v2.6 §1): controllers never reach data-access types directly (the
 * audited path is controller → application service → repository/function), and the low-level
 * platform packages never depend on feature packages.
 */
class ArchitectureTest {

  private static JavaClasses classes;

  @BeforeAll
  static void importClasses() {
    classes =
        new ClassFileImporter()
            .withImportOptions(new ImportOptions().with(ImportOptions.Predefined.DO_NOT_INCLUDE_TESTS))
            .importPackages("com.validexia");
  }

  @Test
  void controllersDoNotUseDataAccessDirectly() {
    noClasses()
        .that()
        .resideInAPackage("..api..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework.jdbc..", "javax.sql..", "..infrastructure..")
        .check(classes);
  }

  @Test
  void infrastructureDoesNotDependOnApiLayer() {
    noClasses()
        .that()
        .resideInAPackage("..infrastructure..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..api..")
        .check(classes);
  }

  @Test
  void platformKernelDoesNotDependOnFeatures() {
    noClasses()
        .that()
        .resideInAnyPackage(
            "com.validexia.platform.config..",
            "com.validexia.platform.errors..",
            "com.validexia.platform.tenancy..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("com.validexia.identity..", "com.validexia.audit..", "com.validexia.api..")
        .check(classes);
  }

  @Test
  void auditDoesNotDependOnIdentityApi() {
    noClasses()
        .that()
        .resideInAPackage("com.validexia.audit..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("com.validexia.identity.api..")
        .check(classes);
  }
}
