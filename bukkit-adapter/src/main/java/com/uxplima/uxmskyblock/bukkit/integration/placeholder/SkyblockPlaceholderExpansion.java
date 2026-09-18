package com.uxplima.uxmskyblock.bukkit.integration.placeholder;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.OfflinePlayer;

import com.uxplima.uxmlib.hook.placeholder.PlaceholderExpansions;
import com.uxplima.uxmlib.hook.placeholder.PlaceholderProvider;
import com.uxplima.uxmlib.hook.placeholder.PlaceholderRegistry;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankPort;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardPort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeStoragePort;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardEntry;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import org.jspecify.annotations.Nullable;

/**
 * PlaceholderAPI expansion for UXM Skyblock.
 *
 * <p>Serves player and island placeholders with zero main-thread database blocking using an
 * asynchronous refreshing in-memory snapshot cache.
 */
public final class SkyblockPlaceholderExpansion implements PlaceholderProvider {

    public static final String IDENTIFIER = "skyblock";
    public static final long CACHE_TTL_MS = 5000L;
    public static final long LEADERBOARD_CACHE_TTL_MS = 15_000L;

    public record CachedPlayerIsland(
            boolean hasIsland,
            @Nullable UUID islandId,
            @Nullable String role,
            int membersCount,
            long bankBalanceMinorUnits,
            long crystals,
            Map<String, Integer> upgradeTiers,
            long cachedAtEpochMs) {

        public static CachedPlayerIsland empty() {
            return new CachedPlayerIsland(
                    false, null, null, 0, 0L, 0L, Collections.emptyMap(), System.currentTimeMillis());
        }

        public boolean isExpired(long ttlMs) {
            return System.currentTimeMillis() - cachedAtEpochMs > ttlMs;
        }
    }

    private final IslandStoragePort islandStoragePort;
    private final IslandBankPort islandBankPort;
    private final IslandUpgradeStoragePort upgradeStoragePort;
    private final IslandLeaderboardPort leaderboardPort;
    private final SchedulerPort schedulerPort;
    private final @Nullable PlayerSessionCoordinator sessionCoordinator;

    private final PlaceholderRegistry registry;
    private final Map<UUID, CachedPlayerIsland> playerCache = new ConcurrentHashMap<>();
    private final Set<UUID> refreshingPlayers = ConcurrentHashMap.newKeySet();

    private final Map<LeaderboardCategory, List<LeaderboardEntry>> cachedLeaderboards = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> cachedPlayerRanks = new ConcurrentHashMap<>();
    private final AtomicBoolean refreshingLeaderboards = new AtomicBoolean(false);
    private volatile long lastLeaderboardRefreshMs = 0;

    public SkyblockPlaceholderExpansion(
            IslandStoragePort islandStoragePort,
            IslandBankPort islandBankPort,
            IslandUpgradeStoragePort upgradeStoragePort,
            IslandLeaderboardPort leaderboardPort,
            SchedulerPort schedulerPort,
            @Nullable PlayerSessionCoordinator sessionCoordinator) {
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.islandBankPort = Objects.requireNonNull(islandBankPort, "islandBankPort must not be null");
        this.upgradeStoragePort = Objects.requireNonNull(upgradeStoragePort, "upgradeStoragePort must not be null");
        this.leaderboardPort = Objects.requireNonNull(leaderboardPort, "leaderboardPort must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.sessionCoordinator = sessionCoordinator;

        this.registry = new PlaceholderRegistry();
        this.registry.fallback(this);
    }

    public SkyblockPlaceholderExpansion(
            IslandStoragePort islandStoragePort,
            IslandBankPort islandBankPort,
            IslandUpgradeStoragePort upgradeStoragePort,
            IslandLeaderboardPort leaderboardPort,
            SchedulerPort schedulerPort) {
        this(islandStoragePort, islandBankPort, upgradeStoragePort, leaderboardPort, schedulerPort, null);
    }

    public PlaceholderRegistry registry() {
        return registry;
    }

    public boolean registerExpansion(String author, String version) {
        return PlaceholderExpansions.register(IDENTIFIER, registry, author, version);
    }

    public void cacheData(UUID playerUuid, CachedPlayerIsland data) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(data, "data");
        playerCache.put(playerUuid, data);
    }

    public void invalidate(UUID playerUuid) {
        if (playerUuid != null) {
            playerCache.remove(playerUuid);
        }
    }

    public CachedPlayerIsland refreshPlayerDataSync(UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        ProfileId profileId = (sessionCoordinator != null)
                ? sessionCoordinator.activeProfile(playerUuid).orElse(null)
                : new ProfileId(playerUuid);
        if (profileId == null) {
            CachedPlayerIsland empty = CachedPlayerIsland.empty();
            playerCache.put(playerUuid, empty);
            return empty;
        }
        Optional<IslandId> optIslandId = islandStoragePort.findIslandIdByProfileId(profileId);

        if (optIslandId.isEmpty()) {
            CachedPlayerIsland empty = CachedPlayerIsland.empty();
            playerCache.put(playerUuid, empty);
            return empty;
        }

        IslandId islandId = optIslandId.get();
        Optional<Island> optIsland = islandStoragePort.findIslandById(islandId);
        if (optIsland.isEmpty()) {
            CachedPlayerIsland empty = CachedPlayerIsland.empty();
            playerCache.put(playerUuid, empty);
            return empty;
        }

        Island island = optIsland.get();
        IslandMember member = island.members().get(profileId);
        String roleStr =
                (member != null && member.role() != null) ? member.role().id() : "";

        IslandBank bank = islandBankPort.findBankByIslandId(islandId).orElse(null);
        long bankBal = (bank != null) ? bank.primaryBalanceMinorUnits() : 0L;
        long bankCryst = (bank != null) ? bank.crystalsBalance() : 0L;

        Map<UpgradeId, Integer> rawUpgrades = upgradeStoragePort.getUpgrades(islandId);
        Map<String, Integer> tiers = new HashMap<>();
        if (rawUpgrades != null) {
            for (Map.Entry<UpgradeId, Integer> entry : rawUpgrades.entrySet()) {
                tiers.put(entry.getKey().key().toLowerCase(Locale.ROOT), entry.getValue());
            }
        }

        CachedPlayerIsland cached = new CachedPlayerIsland(
                true,
                islandId.value(),
                roleStr,
                island.members().size(),
                bankBal,
                bankCryst,
                Collections.unmodifiableMap(tiers),
                System.currentTimeMillis());

        playerCache.put(playerUuid, cached);
        return cached;
    }

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(SkyblockPlaceholderExpansion.class.getName());

    private void scheduleAsyncRefresh(UUID playerUuid) {
        if (refreshingPlayers.add(playerUuid)) {
            schedulerPort.async(() -> {
                try {
                    refreshPlayerDataSync(playerUuid);
                } catch (Exception ex) {
                    LOGGER.log(java.util.logging.Level.FINEST, "Async placeholder refresh failed", ex);
                } finally {
                    refreshingPlayers.remove(playerUuid);
                }
            });
        }
    }

    @Override
    public @Nullable String onRequest(@Nullable OfflinePlayer player, String params) {
        Objects.requireNonNull(params, "params");
        String normalized = params.toLowerCase(Locale.ROOT).trim();

        // Leaderboard global placeholders (independent of player)
        if (normalized.startsWith("leaderboard_top_")) {
            return resolveLeaderboardTop(normalized.substring("leaderboard_top_".length()));
        }

        if (player == null || player.getUniqueId() == null) {
            return null;
        }

        UUID uuid = player.getUniqueId();
        CachedPlayerIsland cached = playerCache.get(uuid);

        if (cached == null) {
            scheduleAsyncRefresh(uuid);
            cached = CachedPlayerIsland.empty();
        } else if (cached.isExpired(CACHE_TTL_MS)) {
            scheduleAsyncRefresh(uuid);
        }

        return switch (normalized) {
            case "has_island" -> String.valueOf(cached.hasIsland());
            case "island_id" -> cached.islandId() != null ? cached.islandId().toString() : "";
            case "island_role", "role" -> cached.role() != null ? cached.role() : "";
            case "island_members_count", "members_count" -> String.valueOf(cached.membersCount());
            case "island_bank_balance", "bank_balance" ->
                String.format(Locale.US, "%.2f", (double) cached.bankBalanceMinorUnits() / 100.0);
            case "island_bank_balance_raw", "bank_balance_raw" -> String.valueOf(cached.bankBalanceMinorUnits());
            case "island_bank_crystals", "bank_crystals" -> String.valueOf(cached.crystals());
            case "island_leaderboard_rank", "rank" -> resolvePlayerRank(cached.islandId());
            default -> {
                if (normalized.startsWith("island_upgrade_tier_")) {
                    String upgradeKey = normalized.substring("island_upgrade_tier_".length());
                    yield String.valueOf(cached.upgradeTiers().getOrDefault(upgradeKey, 0));
                }
                if (normalized.startsWith("upgrade_tier_")) {
                    String upgradeKey = normalized.substring("upgrade_tier_".length());
                    yield String.valueOf(cached.upgradeTiers().getOrDefault(upgradeKey, 0));
                }
                yield null;
            }
        };
    }

    public void checkLeaderboardRefresh() {
        if (System.currentTimeMillis() - lastLeaderboardRefreshMs > LEADERBOARD_CACHE_TTL_MS) {
            scheduleAsyncLeaderboardRefresh();
        }
    }

    public void scheduleAsyncLeaderboardRefresh() {
        if (refreshingLeaderboards.compareAndSet(false, true)) {
            schedulerPort.async(() -> {
                try {
                    refreshLeaderboardsSync();
                } catch (Exception ex) {
                    LOGGER.log(java.util.logging.Level.FINEST, "Async leaderboard refresh failed", ex);
                } finally {
                    refreshingLeaderboards.set(false);
                }
            });
        }
    }

    public void refreshLeaderboardsSync() {
        for (LeaderboardCategory cat : LeaderboardCategory.values()) {
            try {
                List<LeaderboardEntry> top = leaderboardPort.fetchTopIslands(cat, 100);
                cachedLeaderboards.put(cat, top);
                if (cat == LeaderboardCategory.LEVEL) {
                    for (int i = 0; i < top.size(); i++) {
                        cachedPlayerRanks.put(top.get(i).islandId().value(), i + 1);
                    }
                }
            } catch (Exception ex) {
                LOGGER.log(java.util.logging.Level.FINEST, "Failed to refresh leaderboard for " + cat, ex);
            }
        }
        lastLeaderboardRefreshMs = System.currentTimeMillis();
    }

    private String resolvePlayerRank(@Nullable UUID islandId) {
        if (islandId == null) {
            return "N/A";
        }
        checkLeaderboardRefresh();
        Integer rank = cachedPlayerRanks.get(islandId);
        if (rank != null) {
            return String.valueOf(rank);
        }
        try {
            List<LeaderboardEntry> top = leaderboardPort.fetchTopIslands(LeaderboardCategory.LEVEL, 100);
            if (top != null) {
                for (int i = 0; i < top.size(); i++) {
                    cachedPlayerRanks.put(top.get(i).islandId().value(), i + 1);
                    if (top.get(i).islandId().value().equals(islandId)) {
                        rank = i + 1;
                    }
                }
            }
        } catch (Exception ex) {
            LOGGER.log(java.util.logging.Level.FINEST, "Failed to resolve player rank", ex);
        }
        return rank != null ? String.valueOf(rank) : "N/A";
    }

    private @Nullable String resolveLeaderboardTop(String subParams) {
        // subParams format: <category>_<rank>_<field>
        // e.g. "level_1_name" or "level_1_score"
        List<String> parts = com.google.common.base.Splitter.on('_').splitToList(subParams);
        if (parts.size() < 3) {
            return null;
        }

        String catName = parts.get(0);
        LeaderboardCategory category =
                switch (catName) {
                    case "level" -> LeaderboardCategory.LEVEL;
                    case "networth", "worth" -> LeaderboardCategory.WORTH;
                    case "bank" -> LeaderboardCategory.BANK;
                    default -> null;
                };
        if (category == null) {
            return null;
        }

        int rank;
        try {
            rank = Integer.parseInt(parts.get(1));
        } catch (NumberFormatException e) {
            return null;
        }
        if (rank < 1) {
            return null;
        }

        checkLeaderboardRefresh();
        List<LeaderboardEntry> top = cachedLeaderboards.get(category);
        if (top == null || top.size() < rank) {
            try {
                top = leaderboardPort.fetchTopIslands(category, rank);
                if (top != null) {
                    cachedLeaderboards.put(category, top);
                }
            } catch (Exception ex) {
                LOGGER.log(java.util.logging.Level.FINEST, "Failed to resolve leaderboard top", ex);
            }
        }

        String field = parts.get(2);
        if (top != null && top.size() >= rank) {
            LeaderboardEntry entry = top.get(rank - 1);
            if ("score".equals(field)) {
                return String.valueOf(entry.score());
            } else if ("id".equals(field)) {
                return entry.islandId().value().toString();
            }
        }
        return "N/A";
    }
}
