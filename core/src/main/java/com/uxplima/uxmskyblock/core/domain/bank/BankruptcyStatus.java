package com.uxplima.uxmskyblock.core.domain.bank;

import java.util.Locale;
import java.util.Optional;

/**
 * Categorical bankruptcy and upkeep states for an island (Section 2.39).
 */
public enum BankruptcyStatus {

    /**
     * Island is in good standing with zero arrears. Upkeep maintenance fees are current.
     */
    SOLVENT("solvent", "Solvent"),

    /**
     * Island failed an upkeep cycle due to insufficient funds, but remains within the
     * active grace period window (e.g. 72h). All island functions remain operational,
     * but members receive persistent login warnings and debt notices.
     */
    GRACE("grace", "Grace Period"),

    /**
     * Grace period has expired with unpaid arrears. The island is under quarantine lockout:
     * visitor access, spawners, and crop growth are suppressed. Member interactions are restricted
     * strictly to bank deposits and debt queries until arrears are settled.
     */
    LOCKED("locked", "Quarantine Lockout");

    private final String key;
    private final String displayName;

    BankruptcyStatus(String key, String displayName) {
        this.key = key;
        this.displayName = displayName;
    }

    public String key() {
        return key;
    }

    public String displayName() {
        return displayName;
    }

    public static Optional<BankruptcyStatus> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (BankruptcyStatus status : values()) {
            if (status.key.equals(normalized)
                    || status.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }
}
