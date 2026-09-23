package com.uxplima.uxmskyblock.bukkit.effect;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmlib.condition.action.ActionList;
import com.uxplima.uxmlib.text.message.LocaleSource;
import com.uxplima.uxmlib.text.style.Theme;
import com.uxplima.uxmskyblock.bukkit.config.LanguageConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.MessageProvider;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.spongepowered.configurate.BasicConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

/**
 * An operator's effect line is painted with this server's theme, not the library's.
 *
 * <p>uxmLib 0.132 paints the text of a text action through a seam, and a context that leaves it
 * unwired gets the library's default colours. The operator's {@code roles} then reached the menus
 * and never the lines the same operator wrote for an interaction.
 */
class AnEffectLineWearsTheServersThemeTest extends MockBukkitHarness {

    private static final TextColor OPERATORS_BODY = TextColor.color(0x123456);

    @Test
    @DisplayName("A role in an effect line takes the colour the operator gave it")
    void aRoleWearsTheOperatorsColour() throws SerializationException {
        PlayerMock player = createPlayer("Reader");

        new InteractionEffectPlayer(null, messagesWith(OPERATORS_BODY))
                .fire(effects("[message] <body>Welcome home"), "island-create", player);

        Component said = player.nextComponentMessage();
        assertThat(said).isNotNull();
        assertThat(PlainTextComponentSerializer.plainText().serialize(said)).isEqualTo("Welcome home");
        assertThat(colours(said))
                .describedAs("the colours the line was drawn in")
                .contains(OPERATORS_BODY);
    }

    @Test
    @DisplayName("A label token in an effect line is drawn as its word, never as markup")
    void aLabelIsNotMarkup() throws SerializationException {
        PlayerMock player = createPlayer("Reader");

        new InteractionEffectPlayer(null, messagesWith(OPERATORS_BODY))
                .fire(effects("[message] <tag:'ISLAND'> <body>Welcome home"), "island-create", player);

        String said = player.nextMessage();
        assertThat(said)
                .isNotNull()
                .doesNotContain("<tag")
                .doesNotContain("<body>")
                .contains("Welcome home");
    }

    private static Messages messagesWith(TextColor body) throws SerializationException {
        ConfigurationNode root = BasicConfigurationNode.root();
        root.node("roles", "body").set(body.asHexString());
        MessageProvider provider = new MessageProvider(LanguageConfiguration.DEFAULT_LANGUAGE);
        provider.loadBundledDefaults(Messages.class.getClassLoader());
        return new Messages(provider, LocaleSource.ofDefault(java.util.Locale.ENGLISH), Theme.from(root));
    }

    private static List<TextColor> colours(Component component) {
        List<TextColor> found = new java.util.ArrayList<>();
        if (component.color() != null) {
            found.add(component.color());
        }
        for (Component child : component.children()) {
            found.addAll(colours(child));
        }
        return found;
    }

    private static InteractionEffects effects(String line) {
        return new InteractionEffects(Map.of("island-create", ActionList.parse(List.of(line))));
    }
}
