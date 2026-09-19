package com.uxplima.uxmskyblock.bukkit.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Set;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MessageProviderTest {

    private MessageProvider provider;

    @BeforeEach
    void setUp() {
        provider = new MessageProvider("en");
        provider.loadBundledDefaults(getClass().getClassLoader());
    }

    @Test
    @DisplayName("Bundled catalogs for 'en' and 'tr' are loaded successfully")
    void testBundledCatalogsLoaded() {
        Set<String> locales = provider.getAvailableLocales();
        assertThat(locales).contains("en", "tr");

        Set<String> enKeys = provider.getKeys("en");
        Set<String> trKeys = provider.getKeys("tr");

        assertThat(enKeys).isNotEmpty();
        assertThat(trKeys).isNotEmpty();
        assertThat(enKeys).contains("command.create_success", "error.no_island", "bank.deposit_success");
        assertThat(trKeys).contains("command.create_success", "error.no_island", "bank.deposit_success");
    }

    @Test
    @DisplayName("Every message in EN and TR parses cleanly with MiniMessage without throwing")
    void testAllMessagesParseCleanly() {
        for (String locale : provider.getAvailableLocales()) {
            for (String key : provider.getKeys(locale)) {
                if (key.equals("prefix")) {
                    continue;
                }
                assertThatCode(() -> {
                    Component comp = provider.getComponentWithoutPrefix(
                            key,
                            locale,
                            Placeholder.parsed("player", "Steve"),
                            Placeholder.parsed("target", "Alex"),
                            Placeholder.parsed("node", "skyblock-node-01"),
                            Placeholder.parsed("name", "SkyIsle"),
                            Placeholder.parsed("color", "BLUE"),
                            Placeholder.parsed("level", "100"),
                            Placeholder.parsed("points", "5000"),
                            Placeholder.parsed("worth", "100000"),
                            Placeholder.parsed("reason", "Violation"),
                            Placeholder.parsed("max", "5"),
                            Placeholder.parsed("arg", "test"),
                            Placeholder.parsed("block", "DIAMOND_BLOCK"),
                            Placeholder.parsed("count", "10"),
                            Placeholder.parsed("limit", "100"),
                            Placeholder.parsed("entity", "VILLAGER"),
                            Placeholder.parsed("amount", "500.00"),
                            Placeholder.parsed("balance", "1250.00"),
                            Placeholder.parsed("mission", "Master Miner"),
                            Placeholder.parsed("reward", "$5,000"),
                            Placeholder.parsed("current", "50"),
                            Placeholder.parsed("required", "100"),
                            Placeholder.parsed("percent", "50"),
                            Placeholder.parsed("booster", "EXP_2X"),
                            Placeholder.parsed("multiplier", "2.0"),
                            Placeholder.parsed("duration", "30m"),
                            Placeholder.parsed("dimension", "NETHER"),
                            Placeholder.parsed("role", "OWNER"),
                            Placeholder.parsed("island", "Genesis"),
                            Placeholder.parsed("sender", "Steve"),
                            Placeholder.parsed("message", "Hello world!")
                    );
                    assertThat(comp).isNotNull();
                }).as("Failed parsing key '%s' for locale '%s'", key, locale).doesNotThrowAnyException();
            }
        }
    }

    @Test
    @DisplayName("Fallback returns default locale when key is missing in requested locale")
    void testLocaleFallback() {
        // Unknown locale should fall back to 'en'
        Component comp = provider.getComponent("command.create_success", "fr");
        String plain = PlainTextComponentSerializer.plainText().serialize(comp);
        assertThat(plain).contains("Island created successfully");
    }

    @Test
    @DisplayName("Placeholder substitution works as expected with MiniMessage")
    void testPlaceholderSubstitution() {
        Component enComp = provider.getComponent(
                "command.invite_success",
                "en",
                Placeholder.parsed("player", "Notch")
        );
        String enPlain = PlainTextComponentSerializer.plainText().serialize(enComp);
        assertThat(enPlain).contains("Invited Notch to join your island");

        Component trComp = provider.getComponent(
                "command.invite_success",
                "tr",
                Placeholder.parsed("player", "Notch")
        );
        String trPlain = PlainTextComponentSerializer.plainText().serialize(trComp);
        assertThat(trPlain).contains("Notch adlı oyuncu adanıza davet edildi");
    }
}
