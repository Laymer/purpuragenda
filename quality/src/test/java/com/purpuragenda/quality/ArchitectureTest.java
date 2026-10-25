package com.purpuragenda.quality;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.base.DescribedPredicate;
import java.lang.module.ModuleFinder;
import java.util.stream.Collectors;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.*;

class ArchitectureTest {
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Set<String> JDK_PACKAGES = ModuleFinder.ofSystem().findAll().stream()
            .flatMap(module -> module.descriptor().packages().stream()).collect(Collectors.toSet());

    private DescribedPredicate<JavaClass> inward(String layer) {
        return new DescribedPredicate<>("JDK or inward application packages") {
            @Override public boolean test(JavaClass type) {
                String pkg = type.getPackageName();
                return JDK_PACKAGES.contains(pkg) || pkg.equals("com.purpuragenda.domain")
                        || pkg.startsWith("com.purpuragenda.domain.")
                        || (layer.equals("application") && (pkg.equals("com.purpuragenda.application")
                        || pkg.startsWith("com.purpuragenda.application.")));
            }
        };
    }

    @Test void moduleDependenciesPointInward() throws Exception {
        assertDependencies("pom.xml", Set.of());
        assertDependencies("backend/domain/pom.xml", Set.of());
        assertDependencies("backend/application/pom.xml", Set.of("com.purpuragenda:domain"));
        assertDependencies("backend/adapters/pom.xml", Set.of("com.purpuragenda:application"));
        assertDependencies("backend/bootstrap/pom.xml", Set.of("com.purpuragenda:adapters"));
    }

    private void assertDependencies(String file, Set<String> expected) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var document = factory.newDocumentBuilder().parse(ROOT.resolve(file).toFile());
        var dependencies = document.getElementsByTagName("dependency");
        var actual = new java.util.HashSet<String>();
        for (int i = 0; i < dependencies.getLength(); i++) {
            var dependency = (Element) dependencies.item(i);
            var scope = dependency.getElementsByTagName("scope");
            if (scope.getLength() > 0 && Set.of("test", "import").contains(scope.item(0).getTextContent())) continue;
            actual.add(dependency.getElementsByTagName("groupId").item(0).getTextContent() + ":"
                    + dependency.getElementsByTagName("artifactId").item(0).getTextContent());
        }
        // Future adapters/bootstrap may add framework dependencies, never outward module edges.
        if (file.contains("adapters/") || file.contains("bootstrap/")) {
            actual.removeIf(name -> !name.startsWith("com.purpuragenda:"));
        }
        assertEquals(expected, actual, file);
    }

    @Test void productionBytecodeHonorsBoundariesWhenIntroduced() throws Exception {
        var imported = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.purpuragenda.domain", "com.purpuragenda.application", "com.purpuragenda.adapters");
        for (String layer : List.of("domain", "application", "adapters")) {
            Path source = ROOT.resolve("backend/" + layer + "/src/main/java");
            if (Files.exists(source)) {
                try (var files = Files.walk(source)) {
                    if (files.anyMatch(path -> path.toString().endsWith(".java"))) {
                        assertTrue(imported.stream().anyMatch(type -> type.getPackageName()
                                .startsWith("com.purpuragenda." + layer)), "Missing compiled layer " + layer);
                    }
                }
            }
        }
        checkBoundaries(imported);
    }

    private void checkBoundaries(JavaClasses imported) {
        classes().that().resideInAPackage("com.purpuragenda.domain..")
                .should().onlyDependOnClassesThat(inward("domain"))
                .allowEmptyShould(true).check(imported);
        classes().that().resideInAPackage("com.purpuragenda.application..")
                .should().onlyDependOnClassesThat(inward("application"))
                .allowEmptyShould(true).check(imported);
        noClasses().that().resideInAPackage("..adapters.in.rest..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..adapters.out..", "org.springframework.jdbc..", "java.sql..", "javax.sql..")
                .allowEmptyShould(true).check(imported);
        noClasses().that().resideInAnyPackage("com.purpuragenda.domain..", "com.purpuragenda.application..")
                .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..")
                .allowEmptyShould(true).check(imported);
    }

    @Test void architectureRulesRejectFrameworkAndJdbcLeaks() {
        var fixture = new ClassFileImporter().importClasses(JdbcLeak.class);
        assertFalse(inward("domain").test(new ClassFileImporter().importClass(Test.class)));
        assertThrows(AssertionError.class, () -> noClasses().should().dependOnClassesThat()
                .resideInAPackage("java.sql..").check(fixture));
    }

    static class JdbcLeak { java.sql.Connection connection; }
}
