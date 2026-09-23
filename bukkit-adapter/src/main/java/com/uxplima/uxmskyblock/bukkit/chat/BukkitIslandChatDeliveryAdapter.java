package com.uxplima.uxmskyblock.bukkit.chat;

import java.util.Objects;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.config.ChatConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.core.application.chat.IslandChatDeliveryPort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.chat.IslandChatChannel;
import com.uxplima.uxmskyblock.core.domain.chat.IslandChatFrame;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

/**
 * Adventure-native platform implementation of {@link IslandChatDeliveryPort} delivering
 * formatted MiniMessage chat components to Bukkit online players.
 */
public final class BukkitIslandChatDeliveryAdapter implements IslandChatDeliveryPort {

    private final ChatConfiguration configuration;
    private final MiniMessage miniMessage;
    private final SchedulerPort schedulerPort;

    /** Names the sender's role in each recipient's language. */
    private final Messages messages;

    public BukkitIslandChatDeliveryAdapter(ChatConfiguration configuration, SchedulerPort schedulerPort) {
        this(configuration, schedulerPort, Messages.bundled());
    }

    public BukkitIslandChatDeliveryAdapter(
            ChatConfiguration configuration, SchedulerPort schedulerPort, Messages messages) {
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.miniMessage = MiniMessage.miniMessage();
    }

    @Override
    public void deliverToMembers(Set<ProfileId> recipients, IslandChatFrame frame) {
        Objects.requireNonNull(recipients, "recipients must not be null");
        Objects.requireNonNull(frame, "frame must not be null");

        // An alliance message reads differently from an island one, because a player who cannot
        // tell them apart does not know who just heard them.
        // The line is drawn for each recipient, because the sender's role is a word and each reads
        // their own language.
        String format =
                frame.channel() == IslandChatChannel.ALLIANCE ? configuration.allianceFormat() : configuration.format();
        deliver(
                recipients,
                reader -> miniMessage.deserialize(
                        format,
                        Placeholder.parsed("role", roleOf(reader, frame)),
                        Placeholder.parsed("player", frame.senderName()),
                        Placeholder.unparsed("message", frame.message())));
    }

    @Override
    public void deliverToSpies(Set<ProfileId> spies, IslandChatFrame frame, String islandName) {
        Objects.requireNonNull(spies, "spies must not be null");
        Objects.requireNonNull(frame, "frame must not be null");
        Objects.requireNonNull(islandName, "islandName must not be null");

        deliver(
                spies,
                reader -> miniMessage.deserialize(
                        configuration.spyFormat(),
                        Placeholder.parsed("role", roleOf(reader, frame)),
                        Placeholder.parsed("player", frame.senderName()),
                        Placeholder.parsed("island_name", islandName),
                        Placeholder.unparsed("message", frame.message())));
    }

    private String roleOf(Player reader, IslandChatFrame frame) {
        return messages.named(
                reader, "roles", frame.senderRole().id(), frame.senderRole().displayName());
    }

    /**
     * Puts one line in front of each recipient, on the thread that owns them.
     *
     * <p>This runs wherever the message arrived: the scheduler pool on one node, a Redis subscriber
     * thread across a cluster. Neither of those owns a player, and looking one up and writing to
     * them from a thread that does not own them is the thing Folia exists to stop. The lookup and
     * the write both happen on the hop.
     */
    private void deliver(Set<ProfileId> recipients, java.util.function.Function<Player, Component> line) {
        for (ProfileId recipient : recipients) {
            PlayerUuid playerUuid = new PlayerUuid(recipient.value());
            schedulerPort.onEntity(playerUuid, () -> {
                Player player = Bukkit.getPlayer(recipient.value());
                if (player != null && player.isOnline()) {
                    player.sendMessage(line.apply(player));
                }
            });
        }
    }
}
