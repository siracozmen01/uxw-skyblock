package com.uxplima.uxmskyblock.bukkit.chat;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.chat.IslandOnlineMemberProvider;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import org.jspecify.annotations.Nullable;

/**
 * Resolves locally online Bukkit players that are members of a given island using canonical active profiles.
 */
public final class BukkitIslandOnlineMemberProvider implements IslandOnlineMemberProvider {

    private final IslandStoragePort islandStoragePort;
    private final Function<UUID, Optional<ProfileId>> activeProfileProvider;

    public BukkitIslandOnlineMemberProvider(
            IslandStoragePort islandStoragePort, Function<UUID, Optional<ProfileId>> activeProfileProvider) {
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.activeProfileProvider =
                Objects.requireNonNull(activeProfileProvider, "activeProfileProvider must not be null");
    }

    public BukkitIslandOnlineMemberProvider(
            IslandStoragePort islandStoragePort, @Nullable PlayerSessionCoordinator sessionCoordinator) {
        this(
                islandStoragePort,
                sessionCoordinator != null ? sessionCoordinator::activeProfile : uuid -> Optional.empty());
    }

    public BukkitIslandOnlineMemberProvider(IslandStoragePort islandStoragePort) {
        this(islandStoragePort, (PlayerSessionCoordinator) null);
    }

    @Override
    public Set<ProfileId> getOnlineMembers(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");

        Optional<Island> optIsland = islandStoragePort.findIslandById(islandId);
        if (optIsland.isEmpty()) {
            return Set.of();
        }

        Set<ProfileId> islandMembers = optIsland.get().members().keySet();
        Set<ProfileId> online = new HashSet<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            Optional<ProfileId> optActive = activeProfileProvider.apply(player.getUniqueId());
            if (optActive.isPresent() && islandMembers.contains(optActive.get())) {
                online.add(optActive.get());
            }
        }

        return online;
    }
}
