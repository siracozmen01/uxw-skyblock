package com.uxplima.uxmskyblock.bukkit.mission;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.mission.IslandMissionService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionDefinition;
import com.uxplima.uxmskyblock.core.domain.mission.MissionProgress;
import com.uxplima.uxmskyblock.core.domain.mission.MissionTriggerType;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;

/**
 * Event-driven zero-friction listener intercepting player actions and updating
 * mission progress asynchronously without stalling server region tick loops.
 */
public final class IslandMissionListener implements Listener {

    private static final Set<Material> CROPS = Set.of(
            Material.WHEAT,
            Material.CARROTS,
            Material.POTATOES,
            Material.BEETROOTS,
            Material.NETHER_WART,
            Material.SUGAR_CANE,
            Material.CACTUS,
            Material.MELON,
            Material.PUMPKIN);

    private final IslandMissionService missionService;
    private final IslandStoragePort islandStoragePort;
    private final PlayerSessionCoordinator sessionCoordinator;
    private final SchedulerPort schedulerPort;

    public IslandMissionListener(
            IslandMissionService missionService,
            IslandStoragePort islandStoragePort,
            PlayerSessionCoordinator sessionCoordinator,
            SchedulerPort schedulerPort) {
        this.missionService = Objects.requireNonNull(missionService, "missionService must not be null");
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.sessionCoordinator = Objects.requireNonNull(sessionCoordinator, "sessionCoordinator must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Material mat = event.getBlock().getType();
        boolean isCrop = CROPS.contains(mat);
        MissionTriggerType trigger = isCrop ? MissionTriggerType.CROP_HARVEST : MissionTriggerType.BLOCK_BREAK;
        String target = normalizeMaterialName(mat);

        dispatchTrigger(player, trigger, target, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        String target = normalizeMaterialName(event.getBlock().getType());
        dispatchTrigger(player, MissionTriggerType.BLOCK_PLACE, target, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }
        String mobName = event.getEntityType().name().toUpperCase(Locale.ROOT);
        dispatchTrigger(killer, MissionTriggerType.MOB_KILL, mobName, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH) {
            dispatchTrigger(event.getPlayer(), MissionTriggerType.FISHING, "*", 1L);
        }
    }

    private void dispatchTrigger(Player player, MissionTriggerType trigger, String target, long amount) {
        UUID uuid = player.getUniqueId();
        Optional<ProfileId> optProfile = sessionCoordinator.activeProfile(uuid);
        if (optProfile.isEmpty()) {
            return;
        }
        ProfileId profileId = optProfile.get();
        Optional<IslandId> optIsland = islandStoragePort.findIslandIdByProfileId(profileId);
        if (optIsland.isEmpty()) {
            return;
        }
        IslandId islandId = optIsland.get();

        schedulerPort.async(() -> {
            List<MissionProgress> updated = missionService.handleTrigger(
                    islandId, profileId, trigger, target, amount, Instant.now());
            for (MissionProgress progress : updated) {
                if (progress.completed()) {
                    notifyCompletion(player, uuid, progress);
                }
            }
        });
    }

    @SuppressWarnings("EmptyCatch")
    private void notifyCompletion(Player player, UUID uuid, MissionProgress progress) {
        Optional<MissionDefinition> optDef = missionService.findMission(progress.missionId());
        if (optDef.isEmpty()) {
            return;
        }
        MissionDefinition def = optDef.get();

        schedulerPort.onEntity(new PlayerUuid(uuid), () -> {
            if (!player.isOnline()) {
                return;
            }
            player.sendMessage(MiniMessage.miniMessage().deserialize(
                    "<gold><b>[MISSION COMPLETED]</b></gold> <yellow>" + def.displayName() + "</yellow>"));
            try {
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
            } catch (Throwable ignored) {
                // Sound playback failure is non-fatal
            }
        });
    }

    private String normalizeMaterialName(Material mat) {
        String name = mat.name().toUpperCase(Locale.ROOT);
        if (name.equals("CARROTS")) return "CARROT";
        if (name.equals("POTATOES")) return "POTATO";
        if (name.equals("BEETROOTS")) return "BEETROOT";
        return name;
    }
}
