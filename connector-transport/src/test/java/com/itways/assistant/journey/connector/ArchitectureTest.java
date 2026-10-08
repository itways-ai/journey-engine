package com.itways.assistant.journey.connector;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * The line the module's pom draws: a library both journey-service and the
 * engine embed, so no Spring (a stereotype would need a Spring dependency, so
 * "no dependency on Spring" covers annotations too), nothing of common-web
 * beyond its {@code net} package, and nothing of the engine it is a part of.
 */
class ArchitectureTest {

    private static final JavaClasses MODULE = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.itways.assistant.journey.connector");

    @Test
    void noSpring() {
        noClasses().that().resideInAPackage("com.itways.assistant.journey.connector..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework..")
                .because("the transport is wired by its hosts, not by Spring")
                .check(MODULE);
    }

    @Test
    void onlyCommonWebNet() {
        noClasses().that().resideInAPackage("com.itways.assistant.journey.connector..")
                .should().dependOnClassesThat(resideInAPackage("com.itways.web..")
                        .and(not(resideInAPackage("com.itways.web.net.."))))
                .because("common-web's filters, caching and OpenAPI must not ride into the engine")
                .check(MODULE);
    }

    @Test
    void noEngine() {
        noClasses().that().resideInAPackage("com.itways.assistant.journey.connector..")
                .should().dependOnClassesThat().resideInAPackage("com.itways.assistant.journey.engine..")
                .because("the engine depends on the transport, never the other way round")
                .check(MODULE);
    }
}
