package com.uxplima.uxmskyblock.bukkit.reward;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import com.uxplima.uxmskyblock.bukkit.integration.economy.SkyblockEconomyBridge;
import com.uxplima.uxmskyblock.core.application.economy.EconomySagaPort;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.profile.ProfileSwitchPort;
import com.uxplima.uxmskyblock.core.application.reward.RewardDeliveryHandler;
import com.uxplima.uxmskyblock.core.domain.economy.EconomySagaRecord;
import com.uxplima.uxmskyblock.core.domain.economy.SagaId;
import com.uxplima.uxmskyblock.core.domain.economy.SagaState;
import com.uxplima.uxmskyblock.core.domain.economy.SagaType;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentType;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrant;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantComponent;
import org.jspecify.annotations.Nullable;

/**
 * Production reward delivery handler for External Vault economy.
 *
 * <p>Enforces durable idempotency via {@link EconomySagaPort}, ensuring external provider
 * deposits cannot be duplicated upon retries. Correctly resolves canonical {@link PlayerUuid}
 * from {@link ProfileId} without assuming UUID equivalence.
 */
public final class ExternalVaultRewardDeliveryHandler implements RewardDeliveryHandler {

    private final @Nullable Supplier<SkyblockEconomyBridge> economyBridgeSupplier;
    private final @Nullable EconomySagaPort economySagaPort;
    private final @Nullable ProfileSwitchPort profileSwitchPort;
    private final @Nullable IslandStoragePort islandStoragePort;

    public ExternalVaultRewardDeliveryHandler(
            @Nullable Supplier<SkyblockEconomyBridge> economyBridgeSupplier,
            @Nullable EconomySagaPort economySagaPort,
            @Nullable ProfileSwitchPort profileSwitchPort,
            @Nullable IslandStoragePort islandStoragePort) {
        this.economyBridgeSupplier = economyBridgeSupplier;
        this.economySagaPort = economySagaPort;
        this.profileSwitchPort = profileSwitchPort;
        this.islandStoragePort = islandStoragePort;
    }

    public ExternalVaultRewardDeliveryHandler(@Nullable SkyblockEconomyBridge economyBridge) {
        this(economyBridge != null ? () -> economyBridge : null, null, null, null);
    }

    public static ExternalVaultRewardDeliveryHandler ofSupplier(
            @Nullable Supplier<SkyblockEconomyBridge> economyBridgeSupplier) {
        return new ExternalVaultRewardDeliveryHandler(economyBridgeSupplier, null, null, null);
    }

    @Override
    public RewardComponentType supportedType() {
        return RewardComponentType.EXTERNAL_VAULT;
    }

    @Override
    public DeliveryResult deliver(RewardGrant grant, RewardGrantComponent component, ProfileId recipient) {
        Objects.requireNonNull(grant, "grant must not be null");
        Objects.requireNonNull(component, "component must not be null");
        Objects.requireNonNull(recipient, "recipient must not be null");

        SkyblockEconomyBridge economyBridge = economyBridgeSupplier != null ? economyBridgeSupplier.get() : null;
        if (economyBridge == null) {
            return DeliveryResult.failure("External Vault economy bridge is not initialized.");
        }

        double amount = parseAmount(component.payloadData());
        if (amount <= 0.0) {
            return DeliveryResult.failure("Invalid external vault amount: " + component.payloadData());
        }

        // 1. Resolve canonical PlayerUuid without casting ProfileId
        PlayerUuid playerUuid = resolvePlayerUuid(recipient);
        if (playerUuid == null) {
            return DeliveryResult.failure("Unable to resolve player account for profile " + recipient);
        }

        OfflinePlayer player = Bukkit.getOfflinePlayer(playerUuid.value());
        if (!player.hasPlayedBefore() && !player.isOnline()) {
            return DeliveryResult.failure("Player has never played or account is unavailable: " + playerUuid);
        }

        UUID opId = component.componentOperationId().value();
        SagaId sagaId = new SagaId(opId.toString());

        // 2. Enforce durable saga idempotency if saga port is present
        if (economySagaPort != null) {
            Optional<EconomySagaRecord> existingSaga = economySagaPort.findSagaById(sagaId);
            if (existingSaga.isPresent()) {
                SagaState state = existingSaga.get().state();
                if (state == SagaState.COMMITTED) {
                    // Idempotent duplicate: external deposit already succeeded
                    return DeliveryResult.success(opId);
                } else if (state == SagaState.FAILED) {
                    return DeliveryResult.failure("External Vault saga previously failed for operation " + opId);
                }
            } else {
                IslandId islandId = resolveIslandId(recipient);
                long amountMinor = Math.max(1L, Math.round(amount * 100.0));
                EconomySagaRecord newSaga = EconomySagaRecord.start(
                        sagaId,
                        playerUuid,
                        recipient,
                        islandId,
                        SagaType.DEPOSIT,
                        amountMinor,
                        "VAULT",
                        Instant.now().plusSeconds(300),
                        Instant.now());
                economySagaPort.createSaga(newSaga);
            }
        }

        // 3. Execute external deposit with fail-closed UNKNOWN on unexpected failure
        boolean depositSuccess;
        try {
            depositSuccess = economyBridge.depositWallet(player, amount);
        } catch (Exception e) {
            // Do NOT mark FAILED because external provider may have accepted transaction!
            return DeliveryResult.failure("External Vault provider outcome UNKNOWN: " + e.getMessage());
        }

        Instant now = Instant.now();
        if (depositSuccess) {
            if (economySagaPort != null) {
                economySagaPort.updateState(sagaId, SagaState.COMMITTED, now);
            }
            return DeliveryResult.success(opId);
        } else {
            if (economySagaPort != null) {
                economySagaPort.updateState(sagaId, SagaState.FAILED, now);
            }
            return DeliveryResult.failure("External Vault provider rejected deposit of " + amount);
        }
    }

    private PlayerUuid resolvePlayerUuid(ProfileId profileId) {
        if (profileSwitchPort != null) {
            Optional<PlayerUuid> resolved = profileSwitchPort.resolvePlayerUuid(profileId);
            if (resolved.isPresent()) {
                return resolved.get();
            }
        }
        if (islandStoragePort != null) {
            Optional<IslandId> optIslandId = islandStoragePort.findIslandIdByProfileId(profileId);
            if (optIslandId.isPresent()) {
                Optional<Island> optIsland = islandStoragePort.findIslandById(optIslandId.get());
                if (optIsland.isPresent()) {
                    Island island = optIsland.get();
                    IslandMember member = island.members().get(profileId);
                    if (member != null) {
                        return member.playerUuid();
                    } else if (island.ownerProfileId().equals(profileId)) {
                        return island.ownerPlayerUuid();
                    }
                }
            }
        }
        return null;
    }

    private IslandId resolveIslandId(ProfileId profileId) {
        if (islandStoragePort != null) {
            Optional<IslandId> optIsland = islandStoragePort.findIslandIdByProfileId(profileId);
            if (optIsland.isPresent()) {
                return optIsland.get();
            }
        }
        return new IslandId(new UUID(0L, 0L));
    }

    private double parseAmount(String payload) {
        if (payload == null) return 0.0;
        String clean =
                payload.replace("{", "").replace("}", "").replace("\"", "").trim();
        int idx = clean.indexOf("amount:");
        if (idx < 0) return 0.0;
        int end = clean.indexOf(",", idx);
        if (end < 0) end = clean.length();
        try {
            return Double.parseDouble(clean.substring(idx + 7, end).trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
