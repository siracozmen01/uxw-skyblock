package com.uxplima.uxmskyblock.bukkit.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Locale;

import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmlib.text.message.LocaleSource;
import com.uxplima.uxmskyblock.bukkit.config.LanguageConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MessagesTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private Messages messages;

    @BeforeEach
    void setUp() {
        MessageProvider provider = new MessageProvider("en");
        provider.loadBundledDefaults(getClass().getClassLoader());
        messages = new Messages(provider, LocaleSource.ofDefault(Locale.ENGLISH));
    }

    @Test
    @DisplayName("A player whose client speaks Turkish reads the Turkish line")
    void turkishClientReadsTurkish() {
        Player player = playerSpeaking(Locale.of("tr", "TR"));

        assertThat(PLAIN.serialize(messages.render(player, "error.no_island"))).contains("adaya sahip değilsiniz");
    }

    @Test
    @DisplayName("A player whose client speaks English reads the English line")
    void englishClientReadsEnglish() {
        Player player = playerSpeaking(Locale.US);

        assertThat(PLAIN.serialize(messages.render(player, "error.no_island")))
                .contains("do not currently belong to an island");
    }

    @Test
    @DisplayName("A language the plugin does not ship falls back to the server default")
    void unknownLanguageFallsBackToDefault() {
        Player player = playerSpeaking(Locale.FRANCE);

        assertThat(PLAIN.serialize(messages.render(player, "error.no_island")))
                .contains("do not currently belong to an island");
    }

    @Test
    @DisplayName("The console has no client language and reads the server default")
    void consoleReadsTheServerDefault() {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);

        assertThat(PLAIN.serialize(messages.render(console, "error.no_island")))
                .contains("do not currently belong to an island");
    }

    @Test
    @DisplayName("A placeholder reaches the rendered line")
    void placeholderIsSubstituted() {
        Player player = playerSpeaking(Locale.US);

        Component rendered = messages.render(player, "command.invite_success", Placeholder.parsed("player", "Alex"));

        assertThat(PLAIN.serialize(rendered)).contains("Alex");
    }

    @Test
    @DisplayName("send delivers the rendered line to the receiver")
    void sendDeliversToTheReceiver() {
        Player player = playerSpeaking(Locale.US);

        messages.send(player, "error.no_island");

        ArgumentCaptor<Component> sent = ArgumentCaptor.forClass(Component.class);
        verify(player).sendMessage(sent.capture());
        assertThat(PLAIN.serialize(sent.getValue())).contains("do not currently belong to an island");
    }

    @Test
    @DisplayName("render carries the catalogue prefix and renderPlain does not")
    void prefixBelongsToRenderOnly() {
        Player player = playerSpeaking(Locale.US);

        assertThat(PLAIN.serialize(messages.render(player, "error.no_island"))).contains("SKYBLOCK");
        assertThat(PLAIN.serialize(messages.renderPlain(player, "error.no_island")))
                .doesNotContain("SKYBLOCK");
    }

    @Test
    @DisplayName("A key the catalogue does not hold is reported rather than printed at a player")
    void missingKeyIsReported() {
        Player player = playerSpeaking(Locale.US);

        assertThat(messages.has("error.no_island")).isTrue();
        assertThat(messages.has("error.no_such_key_exists")).isFalse();
        assertThat(PLAIN.serialize(messages.render(player, "error.no_such_key_exists")))
                .isEqualTo("error.no_such_key_exists");
    }

    @Test
    @DisplayName("A list valued key renders every line it holds, in file order")
    void listKeyRendersEveryLine() {
        Player player = playerSpeaking(Locale.US);

        List<Component> lines = messages.renderAll(player, "help.lines");

        assertThat(lines).isNotEmpty();
        assertThat(PLAIN.serialize(lines.get(0))).contains("/is menu");
        assertThat(lines).allSatisfy(line -> assertThat(PLAIN.serialize(line)).isNotBlank());
    }

    @Test
    @DisplayName("A list the catalogue does not hold renders nothing rather than a key")
    void missingListRendersNothing() {
        Player player = playerSpeaking(Locale.US);

        assertThat(messages.renderAll(player, "help.no_such_list")).isEmpty();
    }

    @Test
    @DisplayName("A list valued key follows the reader into Turkish")
    void listKeyFollowsTheReader() {
        Player turkish = playerSpeaking(Locale.of("tr"));
        Player english = playerSpeaking(Locale.US);

        String turkishFirst =
                PLAIN.serialize(messages.renderAll(turkish, "help.lines").get(0));
        String englishFirst =
                PLAIN.serialize(messages.renderAll(english, "help.lines").get(0));

        assertThat(turkishFirst).isNotEqualTo(englishFirst);
    }

    @Test
    @DisplayName("A command placeholder in a help line reaches the player as text, not as a tag")
    void helpLinesKeepTheirAngleBrackets() {
        Player player = playerSpeaking(Locale.US);

        assertThat(messages.renderAll(player, "help.lines"))
                .anySatisfy(line -> assertThat(PLAIN.serialize(line)).contains("<type>"));
    }

    @Test
    @DisplayName("With follow-client off every player reads the server language")
    void followClientOffPinsOneLanguage() {
        MessageProvider provider = new MessageProvider("en");
        provider.loadBundledDefaults(getClass().getClassLoader());
        Messages pinned = Messages.of(provider, new LanguageConfiguration("en", false));

        Player turkishClient = playerSpeaking(Locale.of("tr", "TR"));

        assertThat(PLAIN.serialize(pinned.render(turkishClient, "error.no_island")))
                .contains("do not currently belong to an island");
    }

    @Test
    @DisplayName("With follow-client on the same player reads Turkish")
    void followClientOnFollowsTheClient() {
        MessageProvider provider = new MessageProvider("en");
        provider.loadBundledDefaults(getClass().getClassLoader());
        Messages following = Messages.of(provider, new LanguageConfiguration("en", true));

        Player turkishClient = playerSpeaking(Locale.of("tr", "TR"));

        assertThat(PLAIN.serialize(following.render(turkishClient, "error.no_island")))
                .contains("adaya sahip değilsiniz");
    }

    @Test
    @DisplayName("A viewer whose locale cannot be read still gets the server default rather than an error")
    void anUnreadableLocaleFallsBack() {
        Player player = mock(Player.class);
        when(player.locale()).thenReturn(null);

        assertThat(PLAIN.serialize(messages.render(player, "error.no_island")))
                .contains("do not currently belong to an island");
    }

    private static Player playerSpeaking(Locale locale) {
        Player player = mock(Player.class);
        when(player.locale()).thenReturn(locale);
        return player;
    }
}
