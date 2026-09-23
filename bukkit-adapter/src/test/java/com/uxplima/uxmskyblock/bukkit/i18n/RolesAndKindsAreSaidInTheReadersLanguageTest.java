package com.uxplima.uxmskyblock.bukkit.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmskyblock.bukkit.chat.BukkitIslandChatDeliveryAdapter;
import com.uxplima.uxmskyblock.bukkit.config.ChatConfiguration;
import com.uxplima.uxmskyblock.bukkit.test.InlineSchedulerPort;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.domain.booster.BoosterCategory;
import com.uxplima.uxmskyblock.core.domain.chat.IslandChatFrame;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import com.uxplima.uxmskyblock.core.domain.warp.WarpCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A role, a warp category and a booster category are words, so each reader sees their own.
 *
 * <p>On a live server a Turkish player read "(Owner)" in the member list, "GENERAL" for a warp's
 * category and "classic" for the preset in the activity feed: names the code keeps, shown as they
 * were kept. The catalogue now names each, and a role an operator made up keeps the name it was given.
 */
class RolesAndKindsAreSaidInTheReadersLanguageTest extends MockBukkitHarness {

    @Test
    @DisplayName("Every built in role and every warp and booster category has words in English and Turkish")
    void everyKindHasWords() {
        MessageProvider provider = Messages.bundled().provider();
        List<String> keys = new ArrayList<>();
        for (IslandRole role : List.of(
                IslandRole.OWNER, IslandRole.CO_OWNER, IslandRole.MODERATOR, IslandRole.MEMBER, IslandRole.VISITOR)) {
            keys.add("roles." + role.id().toLowerCase(Locale.ROOT));
        }
        for (WarpCategory category : WarpCategory.values()) {
            keys.add("warp.categories." + category.name().toLowerCase(Locale.ROOT));
        }
        for (BoosterCategory category : BoosterCategory.values()) {
            keys.add("booster.categories." + category.name().toLowerCase(Locale.ROOT));
        }
        List<String> missing = new ArrayList<>();
        for (String locale : List.of("en", "tr")) {
            for (String key : keys) {
                if (!provider.getKeys(locale).contains(key)) {
                    missing.add(locale + ": " + key);
                }
            }
        }
        assertThat(missing).isEmpty();
    }

    @Test
    @DisplayName("A built in role reads in each language, and a role an operator made up keeps its name")
    void aRoleIsNamedForTheReader() {
        Messages messages = Messages.bundled();
        PlayerMock turkish = turkish();

        assertThat(messages.named(turkish, "roles", "MEMBER", "Member")).isEqualTo("Üye");
        assertThat(messages.named(createPlayer("Reader"), "roles", "MEMBER", "Member"))
                .isEqualTo("Member");
        assertThat(messages.named(turkish, "roles", "BUILDER", "Builder")).isEqualTo("Builder");
        assertThat(messages.stored("roles", "OWNER", "OWNER")).isEqualTo("@roles.owner");
        assertThat(messages.stored("roles", "BUILDER", "BUILDER")).isEqualTo("BUILDER");
    }

    @Test
    @DisplayName("One island chat line names the sender's role in each recipient's language")
    void chatNamesTheRoleForEachRecipient() {
        PlayerMock turkish = turkish();
        PlayerMock english = createPlayer("Reader");
        BukkitIslandChatDeliveryAdapter chat = new BukkitIslandChatDeliveryAdapter(
                ChatConfiguration.defaultConfiguration(), new InlineSchedulerPort(), Messages.bundled());

        chat.deliverToMembers(
                Set.of(ProfileId.of(turkish.getUniqueId()), ProfileId.of(english.getUniqueId())),
                IslandChatFrame.island(
                        IslandId.of(UUID.randomUUID()),
                        ProfileId.of(UUID.randomUUID()),
                        "Sender",
                        IslandRole.MEMBER,
                        "hello",
                        Instant.now()));

        assertThat(plain(turkish)).contains("Üye").doesNotContain("Member");
        assertThat(plain(english)).contains("Member");
    }

    private PlayerMock turkish() {
        PlayerMock player = createPlayer("Okur");
        player.setLocale(Locale.forLanguageTag("tr"));
        return player;
    }

    private static String plain(PlayerMock player) {
        return PlainTextComponentSerializer.plainText()
                .serialize(Objects.requireNonNull(player.nextComponentMessage()));
    }
}
