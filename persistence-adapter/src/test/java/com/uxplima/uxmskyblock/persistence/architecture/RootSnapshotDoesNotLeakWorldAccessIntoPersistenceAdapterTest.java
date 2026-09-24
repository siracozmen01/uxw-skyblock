package com.uxplima.uxmskyblock.persistence.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The relational snapshot and the persistence adapter know nothing of the world.
 *
 * <p>The game mode architecture names this test. A root's rows are captured and put back by
 * {@code RootRelationalSnapshotPort} in {@code :persistence-adapter}; its blocks and chunks by
 * {@code WorldDimensionSnapshotPort}, whose only implementation lives in {@code :bukkit-adapter}.
 * Neither the adapter's classes nor the snapshot ports may name a Bukkit, Paper or Minecraft type,
 * and none of those types is even on the adapter's classpath at run time.
 */
class RootSnapshotDoesNotLeakWorldAccessIntoPersistenceAdapterTest {

    private static final String[] WORLD_PACKAGES = {"org.bukkit..", "io.papermc..", "org.spigotmc..", "net.minecraft.."
    };

    @Test
    @DisplayName("No persistence adapter class names a Bukkit, Paper or Minecraft type")
    void theAdapterNamesNoWorldType() {
        JavaClasses adapter = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.uxplima.uxmskyblock.persistence");

        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(WORLD_PACKAGES)
                .because("the world is captured in :bukkit-adapter, never through the relational adapter")
                .check(adapter);
    }

    @Test
    @DisplayName("The snapshot ports the adapter implements name no world type either")
    void theSnapshotPortsNameNoWorldType() {
        JavaClasses ports = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.uxplima.uxmskyblock.core.application.snapshot");

        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(WORLD_PACKAGES)
                .check(ports);
    }

    @Test
    @DisplayName("No world or chunk class is on the adapter's classpath to be reached at run time")
    void noWorldClassIsThereAtRunTime() {
        for (String worldClass : new String[] {"org.bukkit.World", "org.bukkit.Chunk", "org.bukkit.block.Block"}) {
            assertThatThrownBy(() -> Class.forName(worldClass))
                    .describedAs(worldClass)
                    .isInstanceOf(ClassNotFoundException.class);
        }
    }
}
