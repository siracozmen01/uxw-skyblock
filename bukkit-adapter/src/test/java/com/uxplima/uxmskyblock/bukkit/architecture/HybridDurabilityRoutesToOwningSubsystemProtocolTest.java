package com.uxplima.uxmskyblock.bukkit.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.uxplima.uxmskyblock.bukkit.architecture.fixtures.shop.BankOutsideItsProtocolFixture;
import com.uxplima.uxmskyblock.bukkit.architecture.fixtures.shop.SwitchOutsideItsUseCaseFixture;
import com.uxplima.uxmskyblock.bukkit.architecture.fixtures.shop.WalletOutsideTheSagaFixture;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankPort;
import com.uxplima.uxmskyblock.core.application.economy.ExternalWalletPort;
import com.uxplima.uxmskyblock.core.application.inventory.JournaledInventoryMutationService;
import com.uxplima.uxmskyblock.core.application.profile.ProfileSwitchPort;
import com.uxplima.uxmskyblock.core.application.profile.SwitchProfileUseCase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Each kind of value moves by the protocol of the subsystem that owns it, and by no other.
 *
 * <p>The testing standard names this test. Items in an inventory move through the inventory journal,
 * an island's balance through the bank's optimistic SQL protocol, a player's wallet through the
 * economy saga, and a profile switch through its operation rows over {@code player_sessions}. A class
 * that moved one of them its own way would be a path none of those protocols can recover after a
 * crash. Each rule below has a fixture it must refuse, so a rule that stopped matching anything would
 * fail here rather than pass quietly.
 */
class HybridDurabilityRoutesToOwningSubsystemProtocolTest {

    private static final Set<String> SWITCH_STEPS = Set.of(
            "reserveSwitch",
            "recordSourceSnapshot",
            "recordTargetLoaded",
            "recordTargetApplyIntent",
            "recordPlayerApplied",
            "commitSwitch",
            "abortSwitch");

    private static JavaClasses production;

    @BeforeAll
    static void importProduction() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.uxplima.uxmskyblock.core", "com.uxplima.uxmskyblock.bukkit");
    }

    static ArchRule theWalletMovesOnlyThroughTheSaga() {
        return noClasses()
                .that()
                .resideOutsideOfPackages("..application.economy..", "..integration.economy..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName(ExternalWalletPort.class.getName())
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("com.uxplima.uxmlib.hook.economy.EconomyBridge")
                .because("a wallet cannot be asked twice, so only the saga, which writes down each ask, asks it");
    }

    static ArchRule aBalanceMovesOnlyThroughTheBankProtocol() {
        return noClasses()
                .that()
                .resideOutsideOfPackages("..application.bank..", "..application.upgrade..")
                .should()
                .callMethodWhere(new DescribedPredicate<JavaMethodCall>("a move of an island's balance") {
                    @Override
                    public boolean test(JavaMethodCall call) {
                        return call.getTarget().getName().equals("executeTransaction")
                                && call.getTargetOwner().isAssignableTo(IslandBankPort.class);
                    }
                })
                .because("a balance moves under the bank's authority, version and operation rows, or not at all");
    }

    static ArchRule aSwitchMovesOnlyThroughItsUseCase() {
        return noClasses()
                .that()
                .doNotHaveFullyQualifiedName(SwitchProfileUseCase.class.getName())
                .and()
                .doNotHaveFullyQualifiedName(ProfileSwitchPort.class.getName())
                .should()
                .callMethodWhere(new DescribedPredicate<JavaMethodCall>("a step of a profile switch") {
                    @Override
                    public boolean test(JavaMethodCall call) {
                        return SWITCH_STEPS.contains(call.getTarget().getName())
                                && call.getTargetOwner().isAssignableTo(ProfileSwitchPort.class);
                    }
                })
                .because("a switch is one operation row carried through its steps by the use case that owns it");
    }

    static ArchRule itemRewardsMoveThroughTheJournal() {
        return classes()
                .that()
                .haveSimpleName("ItemRewardDeliveryHandler")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName(JournaledInventoryMutationService.class.getName())
                .because("an item put into an inventory is written down before it is there");
    }

    @Test
    @DisplayName("The wallet is asked only by the economy saga and its adapter")
    void theWallet() {
        assertThatCode(() -> theWalletMovesOnlyThroughTheSaga().check(production))
                .doesNotThrowAnyException();
        assertThat(theWalletMovesOnlyThroughTheSaga()
                        .evaluate(new ClassFileImporter().importClasses(WalletOutsideTheSagaFixture.class))
                        .hasViolation())
                .isTrue();
    }

    @Test
    @DisplayName("An island's balance moves only through the bank service and the upgrade charge")
    void theBank() {
        assertThatCode(() -> aBalanceMovesOnlyThroughTheBankProtocol().check(production))
                .doesNotThrowAnyException();
        assertThat(aBalanceMovesOnlyThroughTheBankProtocol()
                        .evaluate(new ClassFileImporter().importClasses(BankOutsideItsProtocolFixture.class))
                        .hasViolation())
                .isTrue();
    }

    @Test
    @DisplayName("A profile switch is carried through its steps only by the switch use case")
    void theSwitch() {
        assertThatCode(() -> aSwitchMovesOnlyThroughItsUseCase().check(production))
                .doesNotThrowAnyException();
        assertThat(aSwitchMovesOnlyThroughItsUseCase()
                        .evaluate(new ClassFileImporter().importClasses(SwitchOutsideItsUseCaseFixture.class))
                        .hasViolation())
                .isTrue();
    }

    @Test
    @DisplayName("An item reward goes into the inventory through the inventory journal")
    void theItems() {
        assertThatCode(() -> itemRewardsMoveThroughTheJournal().check(production))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Only the bank adapter writes a balance, and only the session and switch adapters a profile")
    void theRowsHaveOneWriterEach() throws IOException {
        assertThat(writersOf("UPDATE island_banks")).containsExactly("bank/PlayerIslandBankAdapter.java");
        assertThat(writersOf("active_profile_id = "))
                .isNotEmpty()
                .allSatisfy(file -> assertThat(file.startsWith("profile/") || file.startsWith("session/"))
                        .describedAs(file)
                        .isTrue());
    }

    /** The persistence sources that hold {@code sql}, relative to the persistence package. */
    private static List<String> writersOf(String sql) throws IOException {
        Path root = Path.of("../persistence-adapter/src/main/java/com/uxplima/uxmskyblock/persistence");
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> {
                        try {
                            return Files.readString(file).contains(sql);
                        } catch (IOException unreadable) {
                            throw new IllegalStateException(unreadable);
                        }
                    })
                    .map(file -> root.relativize(file).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }
}
