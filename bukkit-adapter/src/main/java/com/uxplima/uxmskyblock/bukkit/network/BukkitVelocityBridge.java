package com.uxplima.uxmskyblock.bukkit.network;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import com.uxplima.uxmskyblock.core.application.network.VelocityBridgePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * Bukkit/Paper implementation of {@link VelocityBridgePort} utilizing standard
 * BungeeCord / Velocity plugin messaging channels to dispatch players cross-server.
 */
public final class BukkitVelocityBridge implements VelocityBridgePort {

    private static final Logger LOGGER = Logger.getLogger(BukkitVelocityBridge.class.getName());
    public static final String BUNGEE_CHANNEL = "BungeeCord";

    private final Plugin plugin;

    public BukkitVelocityBridge(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
        try {
            if (!Bukkit.getMessenger().isOutgoingChannelRegistered(plugin, BUNGEE_CHANNEL)) {
                Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, BUNGEE_CHANNEL);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Unable to register outgoing plugin channel " + BUNGEE_CHANNEL, e);
        }
    }

    @Override
    public CompletableFuture<Boolean> routePlayer(
            PlayerUuid playerUuid, ServerNodeId targetNode, IslandId targetIslandId) {
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        Objects.requireNonNull(targetNode, "targetNode must not be null");
        Objects.requireNonNull(targetIslandId, "targetIslandId must not be null");

        CompletableFuture<Boolean> future = new CompletableFuture<>();

        Player player = Bukkit.getPlayer(playerUuid.value());
        if (player == null || !player.isOnline()) {
            LOGGER.warning(() -> "Cannot route player " + playerUuid.value() + " cross-server: player is offline.");
            future.complete(false);
            return future;
        }

        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("ConnectOther");
            out.writeUTF(player.getName());
            out.writeUTF(targetNode.value());

            player.sendPluginMessage(plugin, BUNGEE_CHANNEL, bytes.toByteArray());
            LOGGER.info(() ->
                    "Dispatched routing request for player " + player.getName() + " to server " + targetNode.value());
            future.complete(true);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to encode Velocity routing packet for player " + playerUuid.value(), e);
            future.complete(false);
        }

        return future;
    }
}
