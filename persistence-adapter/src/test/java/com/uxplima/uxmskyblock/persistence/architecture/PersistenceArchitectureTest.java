package com.uxplima.uxmskyblock.persistence.architecture;

import static com.tngtech.archunit.lang.conditions.ArchConditions.callMethodWhere;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import com.uxplima.uxmskyblock.persistence.architecture.fixtures.PersistenceForbiddenBukkitAdapterFixture;
import com.uxplima.uxmskyblock.persistence.architecture.fixtures.PersistenceForbiddenPlatformFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PersistenceArchitectureTest {

    static ArchRule persistenceMustNotDependOnPlatform() {
        return noClasses()
                .that()
                .resideInAPackage("com.uxplima.uxmskyblock.persistence..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.bukkit..",
                        "io.papermc..",
                        "com.destroystokyo.paper..",
                        "net.kyori.adventure..",
                        "net.minecraft..",
                        "org.spigotmc..")
                .because(
                        ":persistence-adapter must never depend on Minecraft/Bukkit/Paper platform; world access is forbidden")
                .allowEmptyShould(true);
    }

    static ArchRule persistenceMustNotDependOnConfigurate() {
        return noClasses()
                .that()
                .resideInAPackage("com.uxplima.uxmskyblock.persistence..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.spongepowered.configurate..")
                .because(":persistence-adapter must not depend on Configurate UI or bootstrap implementation")
                .allowEmptyShould(true);
    }

    static ArchRule persistenceMustNotDependOnInfrastructureOrExternalPlugins() {
        return noClasses()
                .that()
                .resideInAPackage("com.uxplima.uxmskyblock.persistence..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "net.milkbowl.vault..",
                        "me.clip.placeholderapi..",
                        "org.geysermc.floodgate..",
                        "org.geysermc.geyser..",
                        "io.javalin..",
                        "io.lettuce.core..",
                        "redis.clients..",
                        "software.amazon.awssdk..",
                        "com.uxplima.uxmlib.redis..")
                .because(
                        ":persistence-adapter must not depend on Vault, PlaceholderAPI, Floodgate, Javalin, Redis, or AWS S3")
                .allowEmptyShould(true);
    }

    static ArchRule persistenceMustNotDependOnBukkitAdapter() {
        return noClasses()
                .that()
                .resideInAPackage("com.uxplima.uxmskyblock.persistence..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("com.uxplima.uxmskyblock.bukkit..")
                .because(":persistence-adapter must not depend on :bukkit-adapter")
                .allowEmptyShould(true);
    }

    private static JavaClasses importProductionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.uxplima.uxmskyblock.persistence");
    }

    @Test
    @DisplayName("Production :persistence-adapter classes must not depend on Minecraft/Bukkit/Paper/Adventure")
    void productionPersistenceHasNoPlatformDependencies() {
        JavaClasses production = importProductionClasses();
        assertThatCode(() -> persistenceMustNotDependOnPlatform().check(production))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Production :persistence-adapter classes must not depend on Configurate")
    void productionPersistenceHasNoConfigurateDependencies() {
        JavaClasses production = importProductionClasses();
        assertThatCode(() -> persistenceMustNotDependOnConfigurate().check(production))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Production :persistence-adapter classes must not depend on external plugins/Redis/AWS")
    void productionPersistenceHasNoExternalPluginOrInfrastructureDependencies() {
        JavaClasses production = importProductionClasses();
        assertThatCode(() -> persistenceMustNotDependOnInfrastructureOrExternalPlugins()
                        .check(production))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Production :persistence-adapter classes must not depend on :bukkit-adapter")
    void productionPersistenceHasNoBukkitAdapterDependencies() {
        JavaClasses production = importProductionClasses();
        assertThatCode(() -> persistenceMustNotDependOnBukkitAdapter().check(production))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Verify teeth: guard catches forbidden dependency on Bukkit platform class")
    void guardCatchesForbiddenPlatformDependency() {
        JavaClasses fixture = new ClassFileImporter().importClasses(PersistenceForbiddenPlatformFixture.class);
        EvaluationResult result = persistenceMustNotDependOnPlatform().evaluate(fixture);
        assertThat(result.hasViolation())
                .as("persistenceMustNotDependOnPlatform must catch fixture class importing Bukkit type")
                .isTrue();
    }

    @Test
    @DisplayName("Verify teeth: guard catches forbidden dependency on :bukkit-adapter class")
    void guardCatchesForbiddenBukkitAdapterDependency() {
        JavaClasses fixture = new ClassFileImporter().importClasses(PersistenceForbiddenBukkitAdapterFixture.class);
        EvaluationResult result = persistenceMustNotDependOnBukkitAdapter().evaluate(fixture);
        assertThat(result.hasViolation())
                .as("persistenceMustNotDependOnBukkitAdapter must catch fixture class importing bukkit-adapter type")
                .isTrue();
    }

    static ArchRule noClassesMustCallPrintStackTrace() {
        return noClasses()
                .that()
                .resideInAPackage("com.uxplima.uxmskyblock.persistence..")
                .should(callMethodWhere(com.tngtech.archunit.base.DescribedPredicate.describe(
                        "call printStackTrace()",
                        call -> "java.lang.Throwable"
                                        .equals(call.getTargetOwner().getFullName())
                                && "printStackTrace".equals(call.getName()))))
                .because("printStackTrace() is forbidden; use standard logging or exceptions")
                .allowEmptyShould(true);
    }

    static ArchRule noClassesMustAccessSystemOutOrErr() {
        return noClasses()
                .that()
                .resideInAPackage("com.uxplima.uxmskyblock.persistence..")
                .should()
                .accessField("java.lang.System", "out")
                .orShould()
                .accessField("java.lang.System", "err")
                .because("direct console access via System.out/System.err is forbidden; use logger or diagnostics")
                .allowEmptyShould(true);
    }

    @Test
    @DisplayName("Production :persistence-adapter classes must not call printStackTrace()")
    void productionPersistenceHasNoPrintStackTrace() {
        JavaClasses production = importProductionClasses();
        assertThatCode(() -> noClassesMustCallPrintStackTrace().check(production))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Production :persistence-adapter classes must not access System.out or System.err")
    void productionPersistenceHasNoSystemOutOrErr() {
        JavaClasses production = importProductionClasses();
        assertThatCode(() -> noClassesMustAccessSystemOutOrErr().check(production))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName(
            "Every production package in :persistence-adapter must contain package-info.java annotated with @NullMarked")
    void everyPackageMustHaveNullMarkedPackageInfo() {
        JavaClasses classes = importProductionClasses();
        java.util.Set<String> packagesWithClasses = classes.stream()
                .filter(c -> !c.getSimpleName().equals("package-info"))
                .map(com.tngtech.archunit.core.domain.JavaClass::getPackageName)
                .collect(java.util.stream.Collectors.toSet());
        java.util.Set<String> nullMarkedPackages = classes.stream()
                .filter(c -> c.getSimpleName().equals("package-info"))
                .filter(c -> c.isAnnotatedWith("org.jspecify.annotations.NullMarked"))
                .map(com.tngtech.archunit.core.domain.JavaClass::getPackageName)
                .collect(java.util.stream.Collectors.toSet());
        packagesWithClasses.removeAll(nullMarkedPackages);
        assertThat(packagesWithClasses)
                .as("Packages in :persistence-adapter missing @NullMarked package-info.java: %s", packagesWithClasses)
                .isEmpty();
    }

    static ArchRule noClassesMustCallRawStatementExecution() {
        return noClasses()
                .that()
                .resideInAPackage("com.uxplima.uxmskyblock.persistence..")
                .and()
                .doNotHaveFullyQualifiedName("com.uxplima.uxmskyblock.persistence.backup.SqlDatabaseBackupAdapter")
                .should(callMethodWhere(com.tngtech.archunit.base.DescribedPredicate.describe(
                        "call raw Statement.executeQuery/executeUpdate",
                        call -> "java.sql.Statement"
                                        .equals(call.getTargetOwner().getFullName())
                                && (call.getName().equals("executeQuery")
                                        || call.getName().equals("executeUpdate")
                                        || call.getName().equals("executeLargeUpdate")
                                        || call.getName().equals("addBatch"))
                                && !call.getTarget().getRawParameterTypes().isEmpty()
                                && "java.lang.String"
                                        .equals(call.getTarget()
                                                .getRawParameterTypes()
                                                .get(0)
                                                .getFullName()))))
                .because(
                        "Raw Statement.executeQuery/executeUpdate(sql) is forbidden; all queries and updates must use PreparedStatement with parameters (disaster backup adapter excepted)")
                .allowEmptyShould(true);
    }

    @Test
    @DisplayName("Production :persistence-adapter classes must not call raw Statement.execute*(String)")
    void productionPersistenceHasNoRawStatementExecution() {
        JavaClasses production = importProductionClasses();
        assertThatCode(() -> noClassesMustCallRawStatementExecution().check(production))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Verify teeth: guard catches forbidden raw Statement.execute*(String) call")
    void guardCatchesForbiddenRawStatementExecution() {
        JavaClasses fixture = new ClassFileImporter()
                .importClasses(
                        com.uxplima.uxmskyblock.persistence.architecture.fixtures
                                .PersistenceForbiddenRawStatementFixture.class);
        EvaluationResult result = noClassesMustCallRawStatementExecution().evaluate(fixture);
        assertThat(result.hasViolation())
                .as("noClassesMustCallRawStatementExecution must catch fixture calling raw Statement.executeQuery")
                .isTrue();
    }

    @Test
    @DisplayName("Every @SuppressWarnings annotation in :persistence-adapter must be from an allowed classification")
    void allSuppressWarningsMustBeClassified() {
        java.util.Set<String> allowedClassifications = java.util.Set.of(
                "EmptyCatch",
                "NullAway",
                "NullAway.Init",
                "FieldCanBeLocal",
                "deprecation",
                "unchecked",
                "ArrayRecordComponent",
                "NullablePrimitiveArray",
                "FutureReturnValueIgnored",
                "unused",
                "SelfComparison",
                "removal");

        JavaClasses production = importProductionClasses();
        production.stream()
                .flatMap(c -> java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(c),
                        java.util.stream.Stream.concat(c.getMembers().stream(), c.getMethods().stream())))
                .forEach(element -> {
                    element.tryGetAnnotationOfType(SuppressWarnings.class).ifPresent(ann -> {
                        for (String value : ann.value()) {
                            assertThat(allowedClassifications)
                                    .as("@SuppressWarnings(\"%s\") on %s is unclassified", value, element)
                                    .contains(value);
                        }
                    });
                });
    }
}
