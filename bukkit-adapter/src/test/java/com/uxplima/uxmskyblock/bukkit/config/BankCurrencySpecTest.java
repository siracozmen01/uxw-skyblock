package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/** The currencies an island bank keeps are the operator's list, read from {@code modules/bank.conf}. */
class BankCurrencySpecTest {

    private static ConfigurationNode read(String hocon) throws Exception {
        return HoconConfigurationLoader.builder().buildAndLoadString(hocon);
    }

    @Test
    @DisplayName("The shipped file keeps experience, and lists points and diamonds turned off")
    void theShippedFile() throws Exception {
        ConfigurationNode root = HoconConfigurationLoader.builder()
                .path(Path.of("src/main/resources/modules/bank.conf"))
                .build()
                .load();

        BankConfiguration config = BankConfiguration.load(root);

        assertThat(config.currencies())
                .extracting(BankCurrencySpec::id)
                .containsExactly("experience", "points", "diamonds");
        assertThat(config.enabledCurrencies()).extracting(BankCurrencySpec::id).containsExactly("experience");
        BankCurrencySpec experience = config.currencies().get(0);
        assertThat(experience.type()).isEqualTo(BankCurrencySpec.Type.EXPERIENCE);
        assertThat(experience.option("unit")).hasValue("points");
        assertThat(experience.displayName()).isEqualTo("@bank.currencies.experience");
        assertThat(config.currencies().get(2).option("material")).hasValue("minecraft:diamond");
    }

    @Test
    @DisplayName("Currencies stand in the operator's order, and two of one order by their ids")
    void theOperatorsOrder() throws Exception {
        List<BankCurrencySpec> specs = BankCurrencySpec.load(read("""
                bank.currencies {
                    zeta { type = "experience", order = 5 }
                    beta { type = "PlayerPoints", order = 10 }
                    alpha { type = "item", order = 10, material = "minecraft:emerald" }
                }
                """));

        assertThat(specs).extracting(BankCurrencySpec::id).containsExactly("zeta", "alpha", "beta");
        assertThat(specs.get(2).type()).isEqualTo(BankCurrencySpec.Type.PLAYERPOINTS);
    }

    @Test
    @DisplayName("A currency the file leaves short is on, named by its id, and wears its type's icon")
    void whatTheFileLeavesOut() throws Exception {
        BankCurrencySpec spec = BankCurrencySpec.load(read("bank.currencies.shards { type = \"experience\" }"))
                .get(0);

        assertThat(spec.enabled()).isTrue();
        assertThat(spec.order()).isEqualTo(100);
        assertThat(spec.displayName()).isEqualTo("shards");
        assertThat(spec.icon()).isEqualTo("EXPERIENCE_BOTTLE");
        assertThat(spec.option("unit")).isEmpty();
    }

    @Test
    @DisplayName(
            "A currency of no known type, or under a name the bank keeps for itself, stays out and the rest stay in")
    void aWrongLineClosesNothing() throws Exception {
        List<BankCurrencySpec> specs = BankCurrencySpec.load(read("""
                bank.currencies {
                    gold { type = "goldplugin" }
                    vault { type = "vault" }
                    "two words" { type = "vault" }
                    Coins { type = "vault" }
                }
                """));

        assertThat(specs).extracting(BankCurrencySpec::id).containsExactly("coins");
    }

    @Test
    @DisplayName("A file with no currencies keeps none beside the island's money")
    void noCurrencies() throws Exception {
        assertThat(BankConfiguration.load(read("bank.upkeep.enabled = false")).currencies())
                .isEmpty();
    }
}
