package com.uxplima.uxmskyblock.api.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import com.uxplima.uxmskyblock.api.architecture.fixtures.ApiForbiddenInternalFixture;
import com.uxplima.uxmskyblock.api.architecture.fixtures.ApiForbiddenPlatformFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ApiArchitectureTest {

    static ArchRule apiMustNotDependOnCore() {
        return noClasses()
                .that()
                .resideInAPackage("com.uxplima.uxmskyblock.api..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.uxplima.uxmskyblock.core..")
                .because(":api is the public contract and must not depend on internal :core implementation")
                .allowEmptyShould(true);
    }

    static ArchRule apiMustNotDependOnAdapters() {
        return noClasses()
                .that()
                .resideInAPackage("com.uxplima.uxmskyblock.api..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.uxplima.uxmskyblock.persistence..", "com.uxplima.uxmskyblock.bukkit..", "..adapter..")
                .because(":api is the public contract and must not depend on concrete adapter implementations")
                .allowEmptyShould(true);
    }

    static ArchRule apiMustExposeNoInternalPackages() {
        return noClasses()
                .that()
                .resideInAPackage("com.uxplima.uxmskyblock.api..")
                .should()
                .dependOnClassesThat(DescribedPredicate.describe(
                        "reside in internal non-api package under com.uxplima.uxmskyblock",
                        clazz -> clazz.getPackageName().startsWith("com.uxplima.uxmskyblock.")
                                && !clazz.getPackageName().startsWith("com.uxplima.uxmskyblock.api")))
                .because(":api must expose only its own published public types and standard JDK/JSpecify APIs")
                .allowEmptyShould(true);
    }

    static ArchRule apiMustNotDependOnPlatform() {
        return noClasses()
                .that()
                .resideInAPackage("com.uxplima.uxmskyblock.api..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.bukkit..",
                        "io.papermc..",
                        "com.destroystokyo.paper..",
                        "net.kyori.adventure..",
                        "net.minecraft..",
                        "org.spigotmc..")
                .because(":api must remain platform-neutral and cannot depend on Bukkit, Paper, or Minecraft")
                .allowEmptyShould(true);
    }

    static ArchRule apiMustNotDependOnPersistenceOrSql() {
        return noClasses()
                .that()
                .resideInAPackage("com.uxplima.uxmskyblock.api..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("java.sql..", "javax.sql..", "com.zaxxer.hikari..", "com.uxplima.uxmlib.storage..")
                .because(":api must not depend on database or persistence infrastructure")
                .allowEmptyShould(true);
    }

    static ArchRule apiMustNotDependOnInfrastructureOrExternalPlugins() {
        return noClasses()
                .that()
                .resideInAPackage("com.uxplima.uxmskyblock.api..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.spongepowered.configurate..",
                        "io.lettuce.core..",
                        "redis.clients..",
                        "software.amazon.awssdk..",
                        "io.javalin..",
                        "net.milkbowl.vault..",
                        "me.clip.placeholderapi..",
                        "org.geysermc.floodgate..",
                        "org.geysermc.geyser..",
                        "com.uxplima.uxmlib..")
                .because(":api must not depend on configuration, Redis, object storage, web, or external plugins")
                .allowEmptyShould(true);
    }

    private static JavaClasses importProductionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.uxplima.uxmskyblock.api");
    }

    @Test
    @DisplayName("Production :api classes must not depend on internal :core implementation")
    void productionApiHasNoCoreDependencies() {
        JavaClasses production = importProductionClasses();
        assertThatCode(() -> apiMustNotDependOnCore().check(production)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Production :api classes must not depend on concrete adapter implementations")
    void productionApiHasNoAdapterDependencies() {
        JavaClasses production = importProductionClasses();
        assertThatCode(() -> apiMustNotDependOnAdapters().check(production)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Production :api classes must expose no internal packages")
    void productionApiExposesNoInternalPackages() {
        JavaClasses production = importProductionClasses();
        assertThatCode(() -> apiMustExposeNoInternalPackages().check(production))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Production :api classes must not depend on Minecraft/Bukkit/Paper platform")
    void productionApiHasNoPlatformDependencies() {
        JavaClasses production = importProductionClasses();
        assertThatCode(() -> apiMustNotDependOnPlatform().check(production)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Production :api classes must not depend on SQL/JDBC persistence")
    void productionApiHasNoPersistenceDependencies() {
        JavaClasses production = importProductionClasses();
        assertThatCode(() -> apiMustNotDependOnPersistenceOrSql().check(production))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Production :api classes must not depend on infrastructure or external plugin APIs")
    void productionApiHasNoInfrastructureOrExternalPluginDependencies() {
        JavaClasses production = importProductionClasses();
        assertThatCode(() -> apiMustNotDependOnInfrastructureOrExternalPlugins().check(production))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Verify teeth: guard catches forbidden dependency on internal :core class")
    void guardCatchesForbiddenInternalDependency() {
        JavaClasses fixture = new ClassFileImporter().importClasses(ApiForbiddenInternalFixture.class);
        EvaluationResult result = apiMustNotDependOnCore().evaluate(fixture);
        assertThat(result.hasViolation())
                .as("apiMustNotDependOnCore must catch fixture class importing internal core type")
                .isTrue();
    }

    @Test
    @DisplayName("Verify teeth: guard catches forbidden dependency on Bukkit platform class")
    void guardCatchesForbiddenPlatformDependency() {
        JavaClasses fixture = new ClassFileImporter().importClasses(ApiForbiddenPlatformFixture.class);
        EvaluationResult result = apiMustNotDependOnPlatform().evaluate(fixture);
        assertThat(result.hasViolation())
                .as("apiMustNotDependOnPlatform must catch fixture class importing Bukkit type")
                .isTrue();
    }
}
