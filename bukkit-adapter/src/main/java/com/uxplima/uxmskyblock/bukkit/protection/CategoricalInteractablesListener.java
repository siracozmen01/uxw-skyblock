package com.uxplima.uxmskyblock.bukkit.protection;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

import net.kyori.adventure.text.minimessage.MiniMessage;

import com.uxplima.uxmskyblock.bukkit.config.InteractablesConfiguration;
import com.uxplima.uxmskyblock.core.application.access.TemporaryAccessService;
import com.uxplima.uxmskyblock.core.domain.access.CurrentNodeProcessIdentity;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.permission.PermissionKey;
import com.uxplima.uxmskyblock.core.domain.profile.ProfileType;
import com.uxplima.uxmskyblock.core.domain.session.PlayerSessionRecord;
import org.jspecify.annotations.Nullable;

/**
 * Enterprise categorical interactables listener (Section 2.42 item 7).
 * Maps right-click interactions on doors, redstone triggers, containers, and workstations
 * to granular permission keys rather than generic untyped interaction.
 */
public final class CategoricalInteractablesListener implements Listener {

    private final InteractablesConfiguration config;
    private final Function<Location, Optional<Island>> islandLookup;
    private final Function<PlayerUuid, @Nullable ProfileId> profileLookup;
    private final @Nullable TemporaryAccessService temporaryAccessService;
    private volatile @Nullable Supplier<CurrentNodeProcessIdentity> nodeIdentitySupplier;
    private volatile @Nullable Function<PlayerUuid, Optional<PlayerSessionRecord>> sessionRecordProvider;
    private volatile @Nullable Function<ProfileId, ProfileType> profileTypeProvider;

    public CategoricalInteractablesListener(
            InteractablesConfiguration config,
            Function<Location, Optional<Island>> islandLookup,
            Function<PlayerUuid, @Nullable ProfileId> profileLookup,
            @Nullable TemporaryAccessService temporaryAccessService) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.islandLookup = Objects.requireNonNull(islandLookup, "islandLookup must not be null");
        this.profileLookup = Objects.requireNonNull(profileLookup, "profileLookup must not be null");
        this.temporaryAccessService = temporaryAccessService;
    }

    public void setNodeIdentitySupplier(Supplier<CurrentNodeProcessIdentity> nodeIdentitySupplier) {
        this.nodeIdentitySupplier = nodeIdentitySupplier;
    }

    public void setSessionRecordProvider(Function<PlayerUuid, Optional<PlayerSessionRecord>> sessionRecordProvider) {
        this.sessionRecordProvider = sessionRecordProvider;
    }

    public void setProfileTypeProvider(Function<ProfileId, ProfileType> profileTypeProvider) {
        this.profileTypeProvider = profileTypeProvider;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || !event.hasBlock()) {
            return;
        }

        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }

        Player player = event.getPlayer();
        if (player.hasPermission("uxmskyblock.admin.bypass")) {
            return;
        }

        Optional<PermissionKey> requiredPerm = config.resolvePermission(clicked.getType());
        if (requiredPerm.isEmpty()) {
            return;
        }

        Location loc = clicked.getLocation();
        if (loc.getWorld() == null) {
            return;
        }

        islandLookup.apply(loc).ifPresent(island -> {
            PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
            ProfileId profileId = profileLookup.apply(playerUuid);
            if (profileId == null) {
                event.setCancelled(true);
                return;
            }

            // Island owner always permitted
            if (island.isOwner(profileId)) {
                return;
            }

            // Check temporary access delegation
            if (hasTemporaryAccess(playerUuid, profileId, island, requiredPerm.get())) {
                return;
            }

            // Member check
            if (island.members().containsKey(profileId)) {
                return;
            }

            // Disallowed visitor interaction
            event.setCancelled(true);
            player.sendMessage(MiniMessage.miniMessage()
                    .deserialize("<red>You lack permission to use "
                            + clicked.getType()
                                    .name()
                                    .toLowerCase(java.util.Locale.ROOT)
                                    .replace('_', ' ') + " here.</red>"));
        });
    }

    private boolean hasTemporaryAccess(
            PlayerUuid playerUuid, ProfileId profileId, Island island, PermissionKey permission) {
        if (temporaryAccessService == null) {
            return false;
        }
        CurrentNodeProcessIdentity identity = nodeIdentitySupplier != null
                ? nodeIdentitySupplier.get()
                : new CurrentNodeProcessIdentity("unknown", "default");
        ProfileType type = profileTypeProvider != null ? profileTypeProvider.apply(profileId) : ProfileType.CLASSIC;
        PlayerSessionRecord sessionRecord = sessionRecordProvider != null && playerUuid != null
                ? sessionRecordProvider.apply(playerUuid).orElse(null)
                : null;

        return temporaryAccessService.hasAccess(
                "ISLAND",
                island.id().value().toString(),
                profileId,
                permission,
                Instant.now(),
                identity,
                sessionRecord,
                type);
    }
}
