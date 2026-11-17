package dev.untangle.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import dev.untangle.common.audit.BaseEntity;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The architecture rules from ADR-001, written as tests so they can't silently erode. A violation
 * fails the build with a message naming the offending class and dependency.
 */
class ArchitectureTest {

  private static final String ROOT = "dev.untangle";
  private static JavaClasses mainClasses;

  @BeforeAll
  static void importMainCode() {
    mainClasses =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);
  }

  /**
   * Inside every feature: {@code api → application → domain/infrastructure}. Controllers never
   * reach repositories directly; repositories never call services. Layers that don't exist yet
   * (most features have no api/application code until later phases) are allowed to be empty.
   */
  @Test
  void featureLayersOnlyDependDownwards() {
    layeredArchitecture()
        .consideringOnlyDependenciesInLayers()
        .withOptionalLayers(true)
        .layer("Api")
        .definedBy(ROOT + "..api..")
        .layer("Application")
        .definedBy(ROOT + "..application..")
        .layer("Domain")
        .definedBy(ROOT + "..domain..")
        .layer("Infrastructure")
        .definedBy(ROOT + "..infrastructure..")
        .whereLayer("Api")
        .mayNotBeAccessedByAnyLayer()
        .whereLayer("Application")
        .mayOnlyBeAccessedByLayers("Api")
        .whereLayer("Infrastructure")
        .mayOnlyBeAccessedByLayers("Application")
        .whereLayer("Domain")
        .mayOnlyBeAccessedByLayers("Api", "Application", "Infrastructure")
        .check(mainClasses);
  }

  /** Section 4.2: entities never leave the service layer; controllers speak DTOs only. */
  @Test
  void apiLayerNeverTouchesEntities() {
    noClasses()
        .that()
        .resideInAPackage(ROOT + "..api..")
        .should()
        .dependOnClassesThat()
        .areAnnotatedWith(Entity.class)
        .allowEmptyShould(true)
        .check(mainClasses);
  }

  /**
   * A feature's repositories are private to that feature. Other features must go through its
   * application services, so business rules can't be bypassed via someone else's repository.
   */
  @ParameterizedTest(name = "{0}.infrastructure is private to {0}")
  @ValueSource(
      strings = {
        "agent",
        "ai",
        "auth",
        "comment",
        "dashboard",
        "escalation",
        "notification",
        "ticket",
        "webhook"
      })
  void repositoriesArePrivateToTheirFeature(String feature) {
    String featurePackage = ROOT + "." + feature;
    noClasses()
        .that()
        .resideOutsideOfPackage(featurePackage + "..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage(featurePackage + ".infrastructure..")
        .check(mainClasses);
  }

  /** The domain model is plain Java + JPA annotations; it must not depend on Spring. */
  @Test
  void domainDoesNotDependOnSpring() {
    noClasses()
        .that()
        .resideInAPackage(ROOT + "..domain..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("org.springframework..")
        .check(mainClasses);
  }

  /** Shared code must not know about any feature, or "common" becomes a dumping ground. */
  @Test
  void commonDoesNotDependOnFeatures() {
    noClasses()
        .that()
        .resideInAPackage(ROOT + ".common..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            ROOT + ".agent..",
            ROOT + ".ai..",
            ROOT + ".auth..",
            ROOT + ".comment..",
            ROOT + ".dashboard..",
            ROOT + ".escalation..",
            ROOT + ".notification..",
            ROOT + ".ticket..",
            ROOT + ".webhook..")
        .check(mainClasses);
  }

  /** Every entity gets the UUIDv7 id and identity-safe equals/hashCode from BaseEntity. */
  @Test
  void entitiesExtendBaseEntity() {
    classes()
        .that()
        .areAnnotatedWith(Entity.class)
        .should()
        .beAssignableTo(BaseEntity.class)
        .andShould()
        .resideInAPackage(ROOT + "..domain..")
        .check(mainClasses);
  }

  /** Constructor injection only: dependencies are explicit, final and testable without Spring. */
  @Test
  void noFieldInjection() {
    fields().should().notBeAnnotatedWith(Autowired.class).allowEmptyShould(true).check(mainClasses);
  }
}
