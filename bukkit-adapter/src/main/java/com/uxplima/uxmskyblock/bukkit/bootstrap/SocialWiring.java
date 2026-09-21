package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.Objects;

import com.uxplima.uxmskyblock.bukkit.chat.BukkitIslandChatDeliveryAdapter;
import com.uxplima.uxmskyblock.bukkit.chat.BukkitIslandOnlineMemberProvider;
import com.uxplima.uxmskyblock.bukkit.listener.IslandChatListener;
import com.uxplima.uxmskyblock.core.application.access.TemporaryAccessService;
import com.uxplima.uxmskyblock.core.application.alliance.IslandAllianceService;
import com.uxplima.uxmskyblock.core.application.chat.IslandChatService;
import com.uxplima.uxmskyblock.core.application.chat.IslandChatTransportPort;
import com.uxplima.uxmskyblock.core.application.social.IslandSocialService;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeService;
import com.uxplima.uxmskyblock.core.application.vault.IslandVaultService;
import com.uxplima.uxmskyblock.core.application.warp.IslandWarpService;
import com.uxplima.uxmskyblock.core.application.warp.SafeTeleportEngine;
import com.uxplima.uxmskyblock.core.domain.social.RatingPolicy;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import org.jspecify.annotations.Nullable;

/**
 * Encapsulates social interactions, alliances, temporary access, team chat,
 * warps, and vaults.
 */
public final class SocialWiring {

    private final IslandSocialService socialService;
    private final IslandAllianceService allianceService;
    private final TemporaryAccessService temporaryAccessService;
    private final IslandChatService chatService;
    private final @Nullable IslandChatListener chatListener;
    private final SafeTeleportEngine safeTeleportEngine;
    private final IslandWarpService warpService;
    private final IslandVaultService vaultService;

    public SocialWiring(
            ConfigurationWiring config,
            PersistenceBootstrap persistence,
            AuthorityWiring authority,
            IslandAllianceService allianceService,
            TemporaryAccessService temporaryAccessService,
            IslandUpgradeService upgradeService,
            IslandChatTransportPort chatTransport) {
        this.allianceService = Objects.requireNonNull(allianceService, "allianceService must not be null");
        this.temporaryAccessService =
                Objects.requireNonNull(temporaryAccessService, "temporaryAccessService must not be null");

        this.socialService = new IslandSocialService(
                persistence.islandSocialStoragePort(),
                RatingPolicy.standardFiveStar(),
                persistence.islandStoragePort(),
                config.socialConfig().minDwellTime(),
                config.socialConfig().priorWeight(),
                config.socialConfig().priorMean(),
                config.socialConfig().maxPinned(),
                config.socialConfig().maxMessageLength());

        this.safeTeleportEngine = new SafeTeleportEngine(config.warpConfig().searchRadius());
        this.warpService = new IslandWarpService(
                persistence.islandWarpStoragePort(),
                this.safeTeleportEngine,
                upgradeService,
                config.warpConfig().baseWarpLimit(),
                (targetIslandId, visitorProfileId) -> {
                    if (!config.allianceConfig().privilegedVisitAccess()) {
                        return false;
                    }
                    return persistence
                            .islandStoragePort()
                            .findIslandIdByProfileId(visitorProfileId)
                            .map(visitorIslandId -> allianceService.canPrivilegedVisit(visitorIslandId, targetIslandId))
                            .orElse(false);
                });

        this.vaultService = new IslandVaultService(
                persistence.islandVaultStoragePort(),
                upgradeService,
                config.vaultConfig().basePages(),
                config.vaultConfig().maxPages(),
                config.vaultConfig().leaseDuration());

        IslandChatTransportPort actualChatTransport =
                Objects.requireNonNull(chatTransport, "chatTransport must not be null");
        BukkitIslandChatDeliveryAdapter chatDelivery = new BukkitIslandChatDeliveryAdapter(config.chatConfig());
        BukkitIslandOnlineMemberProvider chatMemberProvider = new BukkitIslandOnlineMemberProvider(
                persistence.islandStoragePort(), authority.activeProfileProvider());
        this.chatService = new IslandChatService(
                persistence.islandStoragePort(),
                actualChatTransport,
                chatDelivery,
                chatMemberProvider,
                config.chatConfig().rateLimitMessagesPerSecond());
        this.chatListener = config.moduleSettings().isModuleEnabled("chat")
                ? new IslandChatListener(this.chatService, authority.activeProfileProvider(), config.messages())
                : null;
    }

    public IslandSocialService socialService() {
        return socialService;
    }

    public IslandAllianceService allianceService() {
        return allianceService;
    }

    public TemporaryAccessService temporaryAccessService() {
        return temporaryAccessService;
    }

    public IslandChatService chatService() {
        return chatService;
    }

    public @Nullable IslandChatListener chatListener() {
        return chatListener;
    }

    public SafeTeleportEngine safeTeleportEngine() {
        return safeTeleportEngine;
    }

    public IslandWarpService warpService() {
        return warpService;
    }

    public IslandVaultService vaultService() {
        return vaultService;
    }
}
