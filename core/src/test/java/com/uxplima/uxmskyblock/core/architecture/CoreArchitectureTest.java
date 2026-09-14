package com.uxplima.uxmskyblock.core.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import com.uxplima.uxmskyblock.core.architecture.fixtures.CoreForbiddenAdapterFixture;
import com.uxplima.uxmskyblock.core.architecture.fixtures.CoreForbiddenPersistenceFixture;
import com.uxplima.uxmskyblock.core.architecture.fixtures.CoreForbiddenPlatformFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CoreArchitectureTest {

    static ArchRule coreMustNotDependOnPlatform() {
        return noClasses()
                .that()
                .resideInAPackage("com.uxplima.uxmskyblock.core..")
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
                        ":core is pure Java domain+application and must not depend on Bukkit, Paper, Adventure, or Minecraft")
                .allowEmptyShould(true);
    }

    static ArchRule coreMustNotDependOnPersistenceOrSql() {
        return noClasses()
                .that()
                .resideInAPackage("com.uxplima.uxmskyblock.core..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("java.sql..", "javax.sql..", "com.zaxxer.hikari..", "com.uxplima.uxmlib.storage..")
                .because(
                        ":core must not depend on SQL, JDBC, Hikari, or uxmlib-storage; persistence is an outbound adapter")
                .allowEmptyShould(true);
    }

    static ArchRule coreMustNotDependOnConfigurate() {
        return noClasses()
                .that()
                .resideInAPackage("com.uxplima.uxmskyblock.core..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.spongepowered.configurate..")
                .because(":core configuration records stay pure Java and must not depend on Configurate")
                .allowEmptyShould(true);
    }

    static ArchRule coreMustNotDependOnInfrastructureOrExternalPlugins() {
        return noClasses()
                .that()
                .resideInAPackage("com.uxplima.uxmskyblock.core..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.lettuce.core..",
                        "redis.clients..",
                        "software.amazon.awssdk..",
                        "io.javalin..",
                        "net.milkbowl.vault..",
                        "me.clip.placeholderapi..",
                        "org.geysermc.floodgate..",
                        "org.geysermc.geyser..")
                .because(":core must not depend on Redis, AWS SDK, Javalin, Vault, PlaceholderAPI, or Floodgate")
                .allowEmptyShould(true);
    }

    static ArchRule coreMustNotDependOnAdapters() {
        return noClasses()
                .that()
                .resideInAPackage("com.uxplima.uxmskyblock.core..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.uxplima.uxmskyblock.persistence..", "com.uxplima.uxmskyblock.bukkit..", "..adapter..")
                .because(":core must not depend on concrete adapter implementations; interaction occurs via ports")
                .allowEmptyShould(true);
    }

    static ArchRule coreMustNotDependOnUxmlibInfrastructure() {
        return noClasses()
                .that()
                .resideInAPackage("com.uxplima.uxmskyblock.core..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.uxplima.uxmlib.common..",
                        "com.uxplima.uxmlib.storage..",
                        "com.uxplima.uxmlib.redis..",
                        "com.uxplima.uxmlib.gui..",
                        "com.uxplima.uxmlib.menu..",
                        "com.uxplima.uxmlib.hud..",
                        "com.uxplima.uxmlib.item..",
                        "com.uxplima.uxmlib.bedrock..",
                        "com.uxplima.uxmlib.condition..",
                        "com.uxplima.uxmlib.integration..")
                .because(":core must not depend on platform-coupled uxm-lib modules")
                .allowEmptyShould(true);
    }

    static ArchRule domainMustNotDependOnApplication() {
        return noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..application..")
                .because("domain layer must not depend on application layer")
                .allowEmptyShould(true);
    }

    static ArchRule domainMustNotDependOnAdaptersOrInfrastructure() {
        return noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..adapter..", "..infrastructure..")
                .because("domain layer must stay completely pure and decoupled from adapters and infrastructure")
                .allowEmptyShould(true);
    }

    static ArchRule applicationMustNotDependOnConcreteAdapters() {
        return noClasses()
                .that()
                .resideInAPackage("..application..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..adapter..")
                .because("application layer must express outbound dependencies through ports, not concrete adapters")
                .allowEmptyShould(true);
    }

    static ArchRule coreMustNotOwnInfrastructureConfiguration() {
        return noClasses()
                .that()
                .resideInAPackage("com.uxplima.uxmskyblock.core..")
                .should()
                .haveSimpleNameEndingWith("DatabaseConfig")
                .orShould()
                .haveSimpleNameEndingWith("StorageConfig")
                .orShould()
                .haveSimpleNameEndingWith("ModulesConfig")
                .because(
                        "infrastructure configuration (DatabaseConfig, StorageConfig, ModulesConfig) belongs to adapter/composition layers, not :core")
                .allowEmptyShould(true);
    }

    static ArchRule domainMustNotDependOnFilesystem() {
        return noClasses()
                .that()
                .resideInAPackage("com.uxplima.uxmskyblock.core.domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("java.nio.file..")
                .because(
                        "pure domain policies and entities must not depend on physical filesystem or java.nio.file types")
                .allowEmptyShould(true);
    }

    private static JavaClasses importProductionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.uxplima.uxmskyblock.core");
    }

    @Test
    @DisplayName("Production :core classes must not depend on Minecraft/Bukkit/Paper/Adventure")
    void productionCoreHasNoPlatformDependencies() {
        JavaClasses production = importProductionClasses();
        assertThatCode(() -> coreMustNotDependOnPlatform().check(production)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Production :core classes must not depend on SQL/JDBC/Hikari/uxmlib-storage")
    void productionCoreHasNoPersistenceDependencies() {
        JavaClasses production = importProductionClasses();
        assertThatCode(() -> coreMustNotDependOnPersistenceOrSql().check(production))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Production :core classes must not depend on Configurate")
    void productionCoreHasNoConfigurateDependencies() {
        JavaClasses production = importProductionClasses();
        assertThatCode(() -> coreMustNotDependOnConfigurate().check(production)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Production :core classes must not depend on infrastructure or external plugin APIs")
    void productionCoreHasNoInfrastructureOrExternalPluginDependencies() {
        JavaClasses production = importProductionClasses();
        assertThatCode(() ->
                        coreMustNotDependOnInfrastructureOrExternalPlugins().check(production))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Production :core classes must not depend on concrete adapter implementations")
    void productionCoreHasNoAdapterDependencies() {
        JavaClasses production = importProductionClasses();
        assertThatCode(() -> coreMustNotDependOnAdapters().check(production)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Production :core classes must not depend on platform-coupled uxm-lib modules")
    void productionCoreHasNoUxmlibInfrastructureDependencies() {
        JavaClasses production = importProductionClasses();
        assertThatCode(() -> coreMustNotDependOnUxmlibInfrastructure().check(production))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Domain packages must not depend on application layer")
    void productionDomainHasNoApplicationDependencies() {
        JavaClasses production = importProductionClasses();
        assertThatCode(() -> domainMustNotDependOnApplication().check(production))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Domain packages must not depend on adapters or infrastructure")
    void productionDomainHasNoAdapterDependencies() {
        JavaClasses production = importProductionClasses();
        assertThatCode(() -> domainMustNotDependOnAdaptersOrInfrastructure().check(production))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Application packages must not depend on concrete adapter implementations")
    void productionApplicationHasNoConcreteAdapterDependencies() {
        JavaClasses production = importProductionClasses();
        assertThatCode(() -> applicationMustNotDependOnConcreteAdapters().check(production))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Verify teeth: guard catches forbidden dependency on Bukkit platform class")
    void guardCatchesForbiddenPlatformDependency() {
        JavaClasses fixture = new ClassFileImporter().importClasses(CoreForbiddenPlatformFixture.class);
        EvaluationResult result = coreMustNotDependOnPlatform().evaluate(fixture);
        assertThat(result.hasViolation())
                .as("coreMustNotDependOnPlatform must catch fixture class importing Bukkit type")
                .isTrue();
    }

    @Test
    @DisplayName("Verify teeth: guard catches forbidden dependency on persistence SQL class")
    void guardCatchesForbiddenPersistenceDependency() {
        JavaClasses fixture = new ClassFileImporter().importClasses(CoreForbiddenPersistenceFixture.class);
        EvaluationResult result = coreMustNotDependOnPersistenceOrSql().evaluate(fixture);
        assertThat(result.hasViolation())
                .as("coreMustNotDependOnPersistenceOrSql must catch fixture class importing java.sql.Connection")
                .isTrue();
    }

    @Test
    @DisplayName("Verify teeth: guard catches forbidden dependency on adapter class")
    void guardCatchesForbiddenAdapterDependency() {
        JavaClasses fixture = new ClassFileImporter().importClasses(CoreForbiddenAdapterFixture.class);
        EvaluationResult result = coreMustNotDependOnAdapters().evaluate(fixture);
        assertThat(result.hasViolation())
                .as("coreMustNotDependOnAdapters must catch fixture class importing adapter type")
                .isTrue();
    }

    @Test
    @DisplayName(
            "Production :core classes must not own infrastructure configuration (DatabaseConfig, StorageConfig, ModulesConfig)")
    void productionCoreHasNoInfrastructureConfiguration() {
        JavaClasses production = importProductionClasses();
        assertThatCode(() -> coreMustNotOwnInfrastructureConfiguration().check(production))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Production :core domain classes must not depend on physical filesystem / java.nio.file")
    void productionDomainHasNoFilesystemDependencies() {
        JavaClasses production = importProductionClasses();
        assertThatCode(() -> domainMustNotDependOnFilesystem().check(production))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Production :core classes must contain real P1-005 domain durability policy classes")
    void productionCoreContainsRealClasses() {
        JavaClasses production = importProductionClasses();
        assertThat(production).isNotEmpty();
        assertThat(production.contain(com.uxplima.uxmskyblock.core.domain.durability.PlayerStateDurabilityConfig.class))
                .isTrue();
        assertThat(production.contain(com.uxplima.uxmskyblock.core.domain.durability.DurabilityMode.class))
                .isTrue();
        assertThat(production.contain(com.uxplima.uxmskyblock.core.domain.durability.DurabilityClassification.class))
                .isTrue();
    }
}
