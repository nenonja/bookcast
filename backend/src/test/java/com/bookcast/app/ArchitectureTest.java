package com.bookcast.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ArchitectureTest {
  @Test
  void moduleBoundaries() {
    ApplicationModules.of(BookcastApplication.class).verify();
  }

  @Test
  void dependencyDirection() {
    var classes = new ClassFileImporter().importPackages("com.bookcast.app");
    noClasses()
        .that()
        .resideInAnyPackage("..domain..", "..application..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("..adapter..", "..contract..", "java.net.http..")
        .check(classes);
    noClasses()
        .that()
        .resideInAnyPackage("..domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework..")
        .check(classes);
    noClasses()
        .that()
        .resideInAnyPackage("..library..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("..generation..")
        .check(classes);
    noClasses()
        .that()
        .haveSimpleNameEndingWith("Controller")
        .should()
        .dependOnClassesThat()
        .haveSimpleNameEndingWith("Store")
        .check(classes);
  }
}
