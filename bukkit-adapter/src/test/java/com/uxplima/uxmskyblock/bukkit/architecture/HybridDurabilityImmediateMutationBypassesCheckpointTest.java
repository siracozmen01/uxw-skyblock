package com.uxplima.uxmskyblock.bukkit.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.uxplima.uxmskyblock.bukkit.architecture.fixtures.shop.ShopThroughTheCheckpointFixture;
import com.uxplima.uxmskyblock.core.application.inventory.ProfileInventoryCheckpointPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A mutation that has to be durable at once is committed by its own protocol, never left for the
 * ambient checkpoint to write.
 *
 * <p>The testing standard names this test. The checkpoint writes a player's whole state once a
 * minute, and a crash loses whatever it had not yet written. That is fine for a block mined and wrong
 * for a sale, a bank move, a reward or a vault page: each of those commits through its own protocol
 * when it happens. The checkpoint port is therefore used by the session that runs the checkpoint, the
 * profile switch that writes the state it leaves, and the journal recovery that repairs it, and by no
 * economic subsystem.
 */
class HybridDurabilityImmediateMutationBypassesCheckpointTest {

    static ArchRule noEconomicSubsystemWritesThroughTheCheckpoint() {
        return noClasses()
                .that()
                .resideOutsideOfPackages("..session..", "..application.profile..", "..application.inventory..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName(ProfileInventoryCheckpointPort.class.getName())
                .because("a value moved now is committed now, by the protocol that owns it");
    }

    @Test
    @DisplayName("Only the session, the profile switch and the journal recovery write through the checkpoint")
    void onlyTheSessionUsesTheCheckpoint() {
        JavaClasses production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.uxplima.uxmskyblock.core", "com.uxplima.uxmskyblock.bukkit");

        assertThatCode(() -> noEconomicSubsystemWritesThroughTheCheckpoint().check(production))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Verify teeth: a shop that leaves a sale for the checkpoint is caught")
    void theRuleHasTeeth() {
        JavaClasses fixture = new ClassFileImporter().importClasses(ShopThroughTheCheckpointFixture.class);

        assertThat(noEconomicSubsystemWritesThroughTheCheckpoint()
                        .evaluate(fixture)
                        .hasViolation())
                .isTrue();
    }
}
