package com.purpuragenda.quality;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {
    @Test void inwardLayersHaveNoFrameworkOrAdapterDependencies() {
        var classes=new ClassFileImporter().importPackages("com.purpuragenda.domain","com.purpuragenda.application","com.purpuragenda.adapters");
        noClasses().that().resideInAnyPackage("..domain..","..application..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..","java.sql..","javax.sql..","jakarta..","..adapters..","..bootstrap..","com.fasterxml..","tools.jackson..")
            .check(classes);
        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("..application..").check(classes);
        noClasses().that().resideInAPackage("..adapters.in.rest..")
            .should().dependOnClassesThat().resideInAnyPackage("..adapters.out..","org.springframework.jdbc..","java.sql..").check(classes);
    }
}
