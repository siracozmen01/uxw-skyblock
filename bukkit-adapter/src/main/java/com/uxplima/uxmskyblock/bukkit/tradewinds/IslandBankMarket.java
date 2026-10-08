package com.uxplima.uxmskyblock.bukkit.tradewinds;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.island.IslandAuthorityPort;
import com.uxplima.uxmskyblock.core.application.tradewinds.PortMarket;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * The island bank, as a port's market asks it: every move keyed, and each answer read for whether the key
 * is spent.
 */
public final class IslandBankMarket implements PortMarket.Bank {

    /** The scope the bank writes a market's keys under. */
    public static final String SCOPE = "TRADEWINDS_MARKET";

    private final IslandBankService bank;
    private final IslandAuthorityPort authority;
    private final ServerNodeId node;
    private final Clock clock;

    public IslandBankMarket(IslandBankService bank, IslandAuthorityPort authority, ServerNodeId node, Clock clock) {
        this.bank = Objects.requireNonNull(bank, "bank");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.node = Objects.requireNonNull(node, "node");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Answer move(IslandId vessel, PlayerUuid actor, long delta, String reason, String key) {
        UUID operation = UUID.nameUUIDFromBytes((SCOPE + ":" + key).getBytes(StandardCharsets.UTF_8));
        return answer(bank.depositToIsland(vessel, actor, delta, reason, node, operation, key, SCOPE));
    }

    @Override
    public boolean here(IslandId vessel) {
        return authority
                .findAuthority(vessel)
                .map(held -> held.authoritativeNode().equals(node)
                        && held.leaseExpiresAt().isAfter(clock.instant()))
                .orElse(false);
    }

    /** What an answer of the bank means for the key it was asked under. */
    static Answer answer(BankTransactionOutcome outcome) {
        return switch (outcome) {
            case BankTransactionOutcome.Success _ -> Answer.LANDED;
            case BankTransactionOutcome.InsufficientFunds _ -> Answer.NO_FUNDS;
            // Written down as refused: the key is spent.
            case BankTransactionOutcome.StaleVersion _ -> Answer.REFUSED;
            case BankTransactionOutcome.BankNotFound _ -> Answer.REFUSED;
            case BankTransactionOutcome.DuplicateOperation duplicate -> duplicate(duplicate);
            // Refused before or without the key being written: ask the same key again later.
            case BankTransactionOutcome.AuthorityRejected rejected ->
                rejected.kind() == BankTransactionOutcome.AuthorityRejected.Kind.OUTCOME_UNKNOWN
                        ? Answer.UNKNOWN
                        : Answer.NOT_NOW;
        };
    }

    private static Answer duplicate(BankTransactionOutcome.DuplicateOperation duplicate) {
        if ("APPLIED".equals(duplicate.status())) {
            return Answer.LANDED;
        }
        if ("REJECTED".equals(duplicate.status())) {
            return "INSUFFICIENT_FUNDS".equals(duplicate.resultCode()) ? Answer.NO_FUNDS : Answer.REFUSED;
        }
        return Answer.UNKNOWN;
    }
}
