package com.uxplima.uxmskyblock.bukkit.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.uxplima.uxmskyblock.bukkit.architecture.fixtures.bank.BankThroughTheInventoryJournalFixture;
import com.uxplima.uxmskyblock.core.application.inventory.InventoryMutationJournalPort;
import com.uxplima.uxmskyblock.core.application.inventory.JournaledInventoryMutationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The inventory journal is the protocol for items in inventories, and for nothing else.
 *
 * <p>The testing standard names this test. A bank balance moves by the bank's own optimistic SQL
 * protocol, a profile switch by its own operation rows, a vault page by its lease, an upgrade by its
 * charge. None of them reaches for the item journal, which would make it a general journal every
 * subsystem shares, and a crash in one would leave intents the others must reconcile.
 */
class HybridDurabilityDoesNotUseUniversalInventoryJournalTest {

    static ArchRule onlyItemsUseTheJournal() {
        return noClasses()
                .that()
                .resideInAnyPackage(
                        "..bank..", "..profile..", "..vault..", "..shop..", "..upgrade..", "..session..", "..booster..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName(InventoryMutationJournalPort.class.getName())
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName(JournaledInventoryMutationService.class.getName())
                .because("the inventory journal is the durability protocol of items, not of every subsystem")
                .allowEmptyShould(true);
    }

    @Test
    @DisplayName("No bank, profile, vault, shop, upgrade, session or booster class uses the inventory journal")
    void onlyItemsUseIt() {
        JavaClasses production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.uxplima.uxmskyblock.core", "com.uxplima.uxmskyblock.bukkit");

        assertThatCode(() -> onlyItemsUseTheJournal().check(production)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Verify teeth: a bank class that uses the inventory journal is caught")
    void theRuleHasTeeth() {
        JavaClasses fixture = new ClassFileImporter().importClasses(BankThroughTheInventoryJournalFixture.class);

        assertThat(onlyItemsUseTheJournal().evaluate(fixture).hasViolation()).isTrue();
    }
}
