package com.uxplima.uxmskyblock.persistence.architecture;

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
}
