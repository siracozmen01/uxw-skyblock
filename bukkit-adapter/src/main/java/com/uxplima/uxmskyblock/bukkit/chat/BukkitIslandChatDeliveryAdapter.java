package com.uxplima.uxmskyblock.bukkit.chat;

import java.util.Objects;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.config.ChatConfiguration;
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

    public BukkitIslandChatDeliveryAdapter(ChatConfiguration configuration, SchedulerPort schedulerPort) {
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.miniMessage = MiniMessage.miniMessage();
    }

    @Override
    public void deliverToMembers(Set<ProfileId> recipients, IslandChatFrame frame) {
        Objects.requireNonNull(recipients, "recipients must not be null");
        Objects.requireNonNull(frame, "frame must not be null");

        // An alliance message reads differently from an island one, because a player who cannot
        // tell them apart does not know who just heard them.
        Component component = miniMessage.deserialize(
                frame.channel() == IslandChatChannel.ALLIANCE ? configuration.allianceFormat() : configuration.format(),
                Placeholder.parsed("role", frame.senderRole().displayName()),
                Placeholder.parsed("player", frame.senderName()),
                Placeholder.unparsed("message", frame.message()));

        deliver(recipients, component);
    }

    @Override
    public void deliverToSpies(Set<ProfileId> spies, IslandChatFrame frame, String islandName) {
        Objects.requireNonNull(spies, "spies must not be null");
        Objects.requireNonNull(frame, "frame must not be null");
        Objects.requireNonNull(islandName, "islandName must not be null");

        Component component = miniMessage.deserialize(
                configuration.spyFormat(),
                Placeholder.parsed("role", frame.senderRole().displayName()),
                Placeholder.parsed("player", frame.senderName()),
                Placeholder.parsed("island_name", islandName),
                Placeholder.unparsed("message", frame.message()));

        deliver(spies, component);
    }

    /**
     * Puts one line in front of each recipient, on the thread that owns them.
     *
     * <p>This runs wherever the message arrived: the scheduler pool on one node, a Redis subscriber
     * thread across a cluster. Neither of those owns a player, and looking one up and writing to
     * them from a thread that does not own them is the thing Folia exists to stop. The lookup and
     * the write both happen on the hop.
     */
    private void deliver(Set<ProfileId> recipients, Component component) {
        for (ProfileId recipient : recipients) {
            PlayerUuid playerUuid = new PlayerUuid(recipient.value());
            schedulerPort.onEntity(playerUuid, () -> {
                Player player = Bukkit.getPlayer(recipient.value());
                if (player != null && player.isOnline()) {
                    player.sendMessage(component);
                }
            });
        }
    }
}
