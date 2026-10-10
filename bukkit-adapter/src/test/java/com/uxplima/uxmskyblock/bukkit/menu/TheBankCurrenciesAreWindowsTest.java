package com.uxplima.uxmskyblock.bukkit.menu;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.uxplima.uxmlib.menu.runtime.MenuContext;
import com.uxplima.uxmlib.menu.spec.ClickKind;
import com.uxplima.uxmskyblock.bukkit.config.BankCurrencySpec;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Every currency the bank keeps beside its money is a tile in the bank window, and its tile opens the window where
 * a member puts some in or takes some out.
 */
class TheBankCurrenciesAreWindowsTest extends MockBukkitHarness {

    @TempDir
    @SuppressWarnings("NullAway.Init")
    Path dataDir;

    private PlayerMock ada;
    private SkyblockMenuEngine engine;
    private final List<BankCurrencySpec> kept = List.of(
            new BankCurrencySpec(
                    "experience",
                    BankCurrencySpec.Type.EXPERIENCE,
                    10,
                    true,
                    "@bank.currencies.experience",
                    "EXPERIENCE_BOTTLE",
                    Map.of()),
            new BankCurrencySpec(
                    "tokens", BankCurrencySpec.Type.PLACEHOLDER, 20, true, "Tokens", "SUNFLOWER", Map.of()));

    @BeforeEach
    void setUp() {
        ada = createPlayer("Ada");
        engine = ShippedTemplates.engineWith(dataDir, "island-bank.conf", "island-bank-currency.conf");
    }

    @Test
    @DisplayName("A currency is a tile of its name, its icon and what the island holds of it")
    void aCurrencyIsATile() {
        BankCurrencyList list = new BankCurrencyList(Messages.bundled(), () -> kept);
        list.register(engine);

        List<MenuRow> rows = list.rows(ada, Map.of("held_experience", "42"));

        assertThat(rows).extracting(row -> row.words().get("id")).containsExactly("experience", "tokens");
        assertThat(rows.get(0).words())
                .containsEntry("name", "Experience")
                .containsEntry("amount", "42")
                .containsEntry("icon", "EXPERIENCE_BOTTLE");
        assertThat(rows.get(1).words()).containsEntry("name", "Tokens").containsEntry("amount", "0");
        MenuContext drawn = MenuContext.of(ada, null, 0).withEntry(rows.get(0));
        var renderer = ShippedTemplates.renderer(engine, Messages.bundled());
        var template = ShippedTemplates.template("island-bank.conf", "currencies");
        assertThat(ShippedTemplates.lore(renderer, template, drawn))
                .contains("Experience")
                .contains("In the bank 42")
                .contains("Click to put some in or take some out")
                .doesNotContain("<entry_");
        assertThat(renderer.materialSpec(template, drawn)).isEqualTo("EXPERIENCE_BOTTLE");
    }

    @Test
    @DisplayName("A click on a tile opens that currency's window with its id, its name and what the island holds")
    void aTileOpensItsWindow() {
        SkyblockMenuEngine watched = org.mockito.Mockito.spy(engine);
        BankCurrencyList list = new BankCurrencyList(Messages.bundled(), () -> kept);
        list.register(watched);
        Map<String, String> bankValues = Map.of("held_experience", "42", "balance", "100");
        MenuRow row = list.rows(ada, bankValues).get(0);

        ShippedTemplates.click(
                watched,
                BankCurrencyList.OPEN,
                MenuContext.of(ada, null, 0, bankValues).withEntry(row),
                ada,
                ClickKind.LEFT,
                "");

        org.mockito.Mockito.verify(watched)
                .open(
                        ada,
                        BankCurrencyList.FILE,
                        Map.of(
                                "held_experience", "42",
                                "balance", "100",
                                "currency", "experience",
                                "currency_name", "Experience",
                                "currency_amount", "42"));
    }

    @Test
    @DisplayName("The window of a currency moves it by the id the bank command reads after an amount")
    void theWindowNamesItsCurrency() {
        var spec = ShippedTemplates.spec("island-bank-currency.conf");

        assertThat(ShippedTemplates.verbs(
                                java.util.Objects.requireNonNull(spec.items().get("deposit")))
                        .toString())
                .contains("skyblock:island:bank deposit %input% %argument_currency%");
        assertThat(ShippedTemplates.verbs(
                                java.util.Objects.requireNonNull(spec.items().get("withdraw")))
                        .toString())
                .contains("skyblock:island:bank withdraw %input% %argument_currency%");
    }
}
