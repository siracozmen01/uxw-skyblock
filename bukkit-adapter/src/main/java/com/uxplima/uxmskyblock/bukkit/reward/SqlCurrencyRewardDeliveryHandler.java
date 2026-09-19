package com.uxplima.uxmskyblock.bukkit.reward;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.reward.RewardDeliveryHandler;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentType;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrant;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantComponent;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * Production reward delivery handler for SQL island bank currency.
 *
 * <p>Executes an atomic bank deposit transaction against the recipient's island bank.
 * Fails closed if the recipient owns no active island or the transaction is rejected by OCC.
 */
public final class SqlCurrencyRewardDeliveryHandler implements RewardDeliveryHandler {

    private final IslandStoragePort islandStoragePort;
    private final IslandBankService bankService;
    private final ServerNodeId nodeId;

    public SqlCurrencyRewardDeliveryHandler(
            IslandStoragePort islandStoragePort, IslandBankService bankService, ServerNodeId nodeId) {
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.bankService = Objects.requireNonNull(bankService, "bankService must not be null");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId must not be null");
    }

    @Override
    public RewardComponentType supportedType() {
        return RewardComponentType.SQL_CURRENCY;
    }

    @Override
    public DeliveryResult deliver(RewardGrant grant, RewardGrantComponent component, ProfileId recipient) {
        Objects.requireNonNull(grant, "grant must not be null");
        Objects.requireNonNull(component, "component must not be null");
        Objects.requireNonNull(recipient, "recipient must not be null");

        // 1. Resolve recipient's island
        Optional<IslandId> optIslandId = islandStoragePort.findIslandIdByProfileId(recipient);
        if (optIslandId.isEmpty()) {
            return DeliveryResult.failure(
                    "Recipient profile " + recipient + " does not belong to an active island to receive bank currency.");
        }
        IslandId islandId = optIslandId.get();

        // 2. Parse currency amount and currency name
        long amountMinorUnits = parseAmount(component.payloadData());
        if (amountMinorUnits <= 0) {
            return DeliveryResult.failure("Invalid currency amount in payload: " + component.payloadData());
        }
        String currency = parseCurrency(component.payloadData());

        // 3. Execute canonical bank deposit
        UUID opId = component.componentOperationId().value();
        BankTransactionOutcome outcome = bankService.depositToIsland(
                islandId,
                new PlayerUuid(recipient.value()),
                amountMinorUnits,
                "Reward Inbox Claim (" + currency + "): " + grant.sourceType(),
                nodeId);

        if (outcome instanceof BankTransactionOutcome.Success) {
            return DeliveryResult.success(opId);
        } else if (outcome instanceof BankTransactionOutcome.DuplicateOperation) {
            return DeliveryResult.success(opId);
        } else if (outcome instanceof BankTransactionOutcome.AuthorityRejected rejected) {
            return DeliveryResult.failure("Bank deposit rejected: " + rejected.reason());
        } else if (outcome instanceof BankTransactionOutcome.StaleVersion) {
            return DeliveryResult.failure("Bank deposit encountered concurrent update; retrying.");
        } else {
            return DeliveryResult.failure("Bank deposit failed: " + outcome.getClass().getSimpleName());
        }
    }

    private long parseAmount(String payload) {
        if (payload == null) return 0L;
        String clean = payload.replace("{", "").replace("}", "").replace("\"", "").trim();
        int idx = clean.indexOf("amount:");
        if (idx < 0) return 0L;
        int end = clean.indexOf(",", idx);
        if (end < 0) end = clean.length();
        try {
            return Long.parseLong(clean.substring(idx + 7, end).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private String parseCurrency(String payload) {
        if (payload == null) return "PRIMARY";
        String clean = payload.replace("{", "").replace("}", "").replace("\"", "").trim();
        int idx = clean.indexOf("currency:");
        if (idx < 0) return "PRIMARY";
        int end = clean.indexOf(",", idx);
        if (end < 0) end = clean.length();
        String c = clean.substring(idx + 9, end).trim();
        return c.isEmpty() ? "PRIMARY" : c;
    }
}
