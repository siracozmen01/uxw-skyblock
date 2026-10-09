package com.uxplima.uxmskyblock.bukkit.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmskyblock.bukkit.config.ChatConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.test.InlineSchedulerPort;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.domain.chat.IslandChatFrame;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * An island chat line names roles of {@code theme.conf}, and the theme says which colour each is.
 *
 * <p>The shipped formats wrote {@code <dark_gray>} and {@code <yellow>}, so the chat kept its own colours
 * whatever palette the server chose, and read as a second product beside every other line.
 */
class ChatIsPaintedFromTheThemeTest extends MockBukkitHarness {

    @Test
    @DisplayName("The sender is drawn in the theme's value colour, under the category the theme draws")
    void theLineTakesTheThemesColours() {
        PlayerMock reader = createPlayer("Reader");
        Messages messages = Messages.bundled();
        BukkitIslandChatDeliveryAdapter chat = new BukkitIslandChatDeliveryAdapter(
                ChatConfiguration.defaultConfiguration(), new InlineSchedulerPort(), messages);

        chat.deliverToMembers(
                Set.of(ProfileId.of(reader.getUniqueId())),
                IslandChatFrame.island(
                        IslandId.of(UUID.randomUUID()),
                        ProfileId.of(UUID.randomUUID()),
                        "Sender",
                        IslandRole.MEMBER,
                        "hello",
                        Instant.now()));

        Component line = Objects.requireNonNull(reader.nextComponentMessage());
        String plain = PlainTextComponentSerializer.plainText().serialize(line);
        assertThat(plain).doesNotContain("<").startsWith("Island ▶").endsWith("hello");
        assertThat(find(line, "Sender", null))
                .describedAs("the sender's name is a value, drawn in the colour the theme gives a value")
                .isEqualTo(messages.styler().theme().colour("value"));
    }

    private static @Nullable TextColor find(Component node, String text, @Nullable TextColor inherited) {
        @Nullable TextColor here = node.color() != null ? node.color() : inherited;
        if (node instanceof TextComponent piece && piece.content().contains(text)) {
            return here;
        }
        for (Component child : node.children()) {
            @Nullable TextColor found = find(child, text, here);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
