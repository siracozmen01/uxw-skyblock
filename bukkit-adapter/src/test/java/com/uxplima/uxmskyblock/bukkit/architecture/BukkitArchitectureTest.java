package com.uxplima.uxmskyblock.bukkit.architecture;

import static com.tngtech.archunit.lang.conditions.ArchConditions.callMethodWhere;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import com.uxplima.uxmskyblock.bukkit.architecture.fixtures.BukkitForbiddenSchedulerFixture;
import com.uxplima.uxmskyblock.bukkit.architecture.fixtures.BukkitForbiddenSqlFixture;
import com.uxplima.uxmskyblock.bukkit.bootstrap.fixtures.BukkitBootstrapAllowedPersistenceWiringFixture;
import com.uxplima.uxmskyblock.bukkit.bootstrap.fixtures.BukkitBootstrapForbiddenSqlFixture;
import com.uxplima.uxmskyblock.bukkit.command.fixtures.BukkitForbiddenPersistenceAdapterFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BukkitArchitectureTest {

    // RULE A: ALL production classes in :bukkit-adapter (including bootstrap) MUST NOT depend on low-level
    // SQL/persistence technology
    static ArchRule bukkitAdapterMustNotDirectlyUseLowLevelPersistence() {
        return noClasses()
                .that()
                .resideInAPackage("com.uxplima.uxmskyblock.bukkit..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("java.sql..", "javax.sql..", "com.zaxxer.hikari..", "com.uxplima.uxmlib.storage..")
                .because(
                        "production code in :bukkit-adapter (including bootstrap/composition) must not directly use low-level SQL, JDBC, Hikari, or uxmlib-storage; persistence technology ownership belongs to :persistence-adapter")
                .allowEmptyShould(true);
    }

    // RULE B: Normal Bukkit adapter packages MUST NOT depend directly on concrete persistence-adapter implementation
    // packages.
    // Composition/bootstrap package (..bootstrap..) MAY reference concrete Skyblock persistence-adapter types only for
    // wiring.
    static ArchRule nonBootstrapBukkitAdapterMustNotDependOnPersistenceImplementations() {
        return noClasses()
                .that()
                .resideInAPackage("com.uxplima.uxmskyblock.bukkit..")
                .and()
                .resideOutsideOfPackage("..bootstrap..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.uxplima.uxmskyblock.persistence..")
                .because(
                        "business, event, command, and UI classes in :bukkit-adapter must depend on application/core ports, not concrete persistence adapter implementation types; only composition root (..bootstrap..) may wire them")
                .allowEmptyShould(true);
    }

    // Redis & Object Storage Defense: prohibit direct dependencies on raw Redis or AWS S3 clients
    static ArchRule bukkitAdapterMustNotDirectlyUseRawRedisOrAws() {
        return noClasses()
                .that()
                .resideInAPackage("com.uxplima.uxmskyblock.bukkit..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.lettuce.core..",
                        "redis.clients..",
                        "com.uxplima.uxmlib.redis..",
                        "software.amazon.awssdk..")
                .because(
                        "production code in :bukkit-adapter must not instantiate raw Redis or AWS S3 clients directly; transport belongs to dedicated outbound adapters")
                .allowEmptyShould(true);
    }

    // RULE C: Command handlers must not depend directly on low-level IslandStoragePort, IslandAuthorityPort, or
    // IslandBankPort
    static ArchRule commandHandlersMustNotDirectlyUseLowLevelStoragePorts() {
        return noClasses()
                .that()
                .resideInAPackage("com.uxplima.uxmskyblock.bukkit.command..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("com.uxplima.uxmskyblock.core.application.island.IslandStoragePort")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("com.uxplima.uxmskyblock.core.application.island.IslandAuthorityPort")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("com.uxplima.uxmskyblock.core.application.bank.IslandBankPort")
                .because(
                        "command handlers must orchestrate actions through application use cases and services rather than directly querying storage ports")
                .allowEmptyShould(true);
    }

    // Folia / Legacy Scheduler Fence
    static ArchRule bukkitAdapterMustNotDependOnLegacySchedulers() {
        return noClasses()
                .that()
                .resideInAPackage("com.uxplima.uxmskyblock.bukkit..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("org.bukkit.scheduler.BukkitScheduler")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("org.bukkit.scheduler.BukkitRunnable")
                .because(
                        "legacy BukkitScheduler and BukkitRunnable are forbidden on Folia; use Folia region/global/async schedulers or internal Scheduler port")
                .allowEmptyShould(true);
    }

    static ArchRule bukkitAdapterMustNotCallBukkitGetScheduler() {
        return noClasses()
                .that()
                .resideInAPackage("com.uxplima.uxmskyblock.bukkit..")
                .should(callMethodWhere(callingBukkitGetScheduler()))
                .because(
                        "calling Bukkit.getScheduler() is forbidden on Folia; use Folia region/global/async schedulers or internal Scheduler port")
                .allowEmptyShould(true);
    }

    private static DescribedPredicate<JavaMethodCall> callingBukkitGetScheduler() {
        return DescribedPredicate.describe(
                "call Bukkit.getScheduler()",
                call -> "org.bukkit.Bukkit".equals(call.getTargetOwner().getFullName())
                        && "getScheduler".equals(call.getName()));
    }

    private static JavaClasses importProductionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.uxplima.uxmskyblock.bukkit");
    }

    @Test
    @DisplayName("Production :bukkit-adapter classes must not directly use low-level SQL/JDBC/storage drivers")
    void productionBukkitAdapterHasNoLowLevelPersistenceDependencies() {
        JavaClasses production = importProductionClasses();
        assertThatCode(() ->
                        bukkitAdapterMustNotDirectlyUseLowLevelPersistence().check(production))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Non-bootstrap :bukkit-adapter classes must not depend on concrete persistence implementations")
    void nonBootstrapBukkitAdapterHasNoPersistenceImplementationDependencies() {
        JavaClasses production = importProductionClasses();
        assertThatCode(() -> nonBootstrapBukkitAdapterMustNotDependOnPersistenceImplementations()
                        .check(production))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Command handlers must not directly depend on storage ports (must use application use cases)")
    void commandHandlersDoNotDirectlyDependOnStoragePorts() {
        JavaClasses production = importProductionClasses();
        assertThatCode(() ->
                        commandHandlersMustNotDirectlyUseLowLevelStoragePorts().check(production))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Production :bukkit-adapter classes must not directly use raw Redis or AWS SDK")
    void productionBukkitAdapterHasNoRawRedisOrAwsDependencies() {
        JavaClasses production = importProductionClasses();
        assertThatCode(() -> bukkitAdapterMustNotDirectlyUseRawRedisOrAws().check(production))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Production :bukkit-adapter classes must not depend on BukkitScheduler or BukkitRunnable")
    void productionBukkitAdapterHasNoLegacySchedulerDependencies() {
        JavaClasses production = importProductionClasses();
        assertThatCode(() -> bukkitAdapterMustNotDependOnLegacySchedulers().check(production))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Production :bukkit-adapter classes must not call Bukkit.getScheduler()")
    void productionBukkitAdapterDoesNotCallBukkitGetScheduler() {
        JavaClasses production = importProductionClasses();
        assertThatCode(() -> bukkitAdapterMustNotCallBukkitGetScheduler().check(production))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Verify teeth: guard catches forbidden dependency on BukkitScheduler")
    void guardCatchesForbiddenLegacySchedulerDependency() {
        JavaClasses fixture = new ClassFileImporter().importClasses(BukkitForbiddenSchedulerFixture.class);
        EvaluationResult result = bukkitAdapterMustNotDependOnLegacySchedulers().evaluate(fixture);
        assertThat(result.hasViolation())
                .as("bukkitAdapterMustNotDependOnLegacySchedulers must catch fixture depending on BukkitScheduler")
                .isTrue();
    }

    @Test
    @DisplayName("Verify teeth: guard catches call to Bukkit.getScheduler()")
    void guardCatchesCallToBukkitGetScheduler() {
        JavaClasses fixture = new ClassFileImporter().importClasses(BukkitForbiddenSchedulerFixture.class);
        EvaluationResult result = bukkitAdapterMustNotCallBukkitGetScheduler().evaluate(fixture);
        assertThat(result.hasViolation())
                .as("bukkitAdapterMustNotCallBukkitGetScheduler must catch call to Bukkit.getScheduler()")
                .isTrue();
    }

    @Test
    @DisplayName("Verify teeth: guard catches forbidden direct SQL dependency")
    void guardCatchesForbiddenSqlDependency() {
        JavaClasses fixture = new ClassFileImporter().importClasses(BukkitForbiddenSqlFixture.class);
        EvaluationResult result =
                bukkitAdapterMustNotDirectlyUseLowLevelPersistence().evaluate(fixture);
        assertThat(result.hasViolation())
                .as(
                        "bukkitAdapterMustNotDirectlyUseLowLevelPersistence must catch fixture class importing java.sql.Connection")
                .isTrue();
    }

    @Test
    @DisplayName("Verify teeth: guard catches low-level SQL dependency even inside bootstrap package")
    void guardCatchesForbiddenSqlDependencyInBootstrapPackage() {
        JavaClasses fixture = new ClassFileImporter().importClasses(BukkitBootstrapForbiddenSqlFixture.class);
        EvaluationResult result =
                bukkitAdapterMustNotDirectlyUseLowLevelPersistence().evaluate(fixture);
        assertThat(result.hasViolation())
                .as(
                        "bukkitAdapterMustNotDirectlyUseLowLevelPersistence must catch SQL dependency even inside bootstrap package")
                .isTrue();
    }

    @Test
    @DisplayName("Verify teeth: guard catches concrete persistence implementation dependency in non-bootstrap package")
    void guardCatchesPersistenceImplementationInNonBootstrapPackage() {
        JavaClasses fixture = new ClassFileImporter().importClasses(BukkitForbiddenPersistenceAdapterFixture.class);
        EvaluationResult result = nonBootstrapBukkitAdapterMustNotDependOnPersistenceImplementations()
                .evaluate(fixture);
        assertThat(result.hasViolation())
                .as(
                        "nonBootstrapBukkitAdapterMustNotDependOnPersistenceImplementations must catch persistence implementation in non-bootstrap package")
                .isTrue();
    }

    @Test
    @DisplayName("Verify teeth: bootstrap package IS allowed to wire concrete persistence implementations")
    void bootstrapPackageIsAllowedToWirePersistenceImplementations() {
        JavaClasses fixture =
                new ClassFileImporter().importClasses(BukkitBootstrapAllowedPersistenceWiringFixture.class);
        EvaluationResult result = nonBootstrapBukkitAdapterMustNotDependOnPersistenceImplementations()
                .evaluate(fixture);
        assertThat(result.hasViolation())
                .as(
                        "nonBootstrapBukkitAdapterMustNotDependOnPersistenceImplementations must permit persistence implementation wiring in bootstrap package")
                .isFalse();
    }

    @Test
    @DisplayName("Production :bukkit-adapter classes must contain real P1-005 configuration adapter classes")
    void productionBukkitContainsRealClasses() {
        JavaClasses production = importProductionClasses();
        assertThat(production).isNotEmpty();
        assertThat(production.contain(com.uxplima.uxmskyblock.bukkit.config.PlayerStateConfigurationAdapter.class))
                .isTrue();
    }
}
