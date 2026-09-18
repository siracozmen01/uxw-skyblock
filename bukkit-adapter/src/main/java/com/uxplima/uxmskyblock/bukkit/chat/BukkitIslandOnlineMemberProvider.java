package com.uxplima.uxmskyblock.bukkit.chat;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.uxplima.uxmskyblock.core.application.chat.IslandOnlineMemberProvider;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;

/**
 * Resolves locally online Bukkit players that are members of a given island.
 */
public final class BukkitIslandOnlineMemberProvider implements IslandOnlineMemberProvider {

    private final IslandStoragePort islandStoragePort;

    public BukkitIslandOnlineMemberProvider(IslandStoragePort islandStoragePort) {
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
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
            ProfileId profileId = ProfileId.of(player.getUniqueId());
            if (islandMembers.contains(profileId)) {
                online.add(profileId);
            }
        }

        return online;
    }
}
