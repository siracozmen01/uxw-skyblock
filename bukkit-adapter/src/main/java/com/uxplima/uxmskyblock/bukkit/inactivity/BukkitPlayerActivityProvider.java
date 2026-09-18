package com.uxplima.uxmskyblock.bukkit.inactivity;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import com.uxplima.uxmskyblock.core.application.inactivity.PlayerActivityProvider;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

/**
 * Platform adapter resolving player activity timestamps from live Bukkit sessions
 * or persisted offline player data.
 */
public final class BukkitPlayerActivityProvider implements PlayerActivityProvider {

    @Override
    @SuppressWarnings("deprecation")
    public Optional<Instant> getLastActive(PlayerUuid playerUuid, ProfileId profileId) {
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");

        Player onlinePlayer = Bukkit.getPlayer(playerUuid.value());
        if (onlinePlayer != null && onlinePlayer.isOnline()) {
            return Optional.of(Instant.now());
        }

        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerUuid.value());
        long lastPlayed = offlinePlayer.getLastPlayed();
        if (lastPlayed > 0L) {
            return Optional.of(Instant.ofEpochMilli(lastPlayed));
        }

        return Optional.empty();
    }
}
