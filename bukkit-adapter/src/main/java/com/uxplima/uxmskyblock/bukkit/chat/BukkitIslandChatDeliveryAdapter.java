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
import com.uxplima.uxmskyblock.core.domain.chat.IslandChatFrame;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

/**
 * Adventure-native platform implementation of {@link IslandChatDeliveryPort} delivering
 * formatted MiniMessage chat components to Bukkit online players.
 */
public final class BukkitIslandChatDeliveryAdapter implements IslandChatDeliveryPort {

    private final ChatConfiguration configuration;
    private final MiniMessage miniMessage;

    public BukkitIslandChatDeliveryAdapter(ChatConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.miniMessage = MiniMessage.miniMessage();
    }

    @Override
    public void deliverToMembers(Set<ProfileId> recipients, IslandChatFrame frame) {
        Objects.requireNonNull(recipients, "recipients must not be null");
        Objects.requireNonNull(frame, "frame must not be null");

        Component component = miniMessage.deserialize(
                configuration.format(),
                Placeholder.parsed("role", frame.senderRole().displayName()),
                Placeholder.parsed("player", frame.senderName()),
                Placeholder.unparsed("message", frame.message()));

        for (ProfileId recipient : recipients) {
            Player player = Bukkit.getPlayer(recipient.value());
            if (player != null && player.isOnline()) {
                player.sendMessage(component);
            }
        }
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

        for (ProfileId spy : spies) {
            Player player = Bukkit.getPlayer(spy.value());
            if (player != null && player.isOnline()) {
                player.sendMessage(component);
            }
        }
    }
}
