package com.uxplima.uxmskyblock.core.domain.storage;

import java.util.Objects;

/**
 * Domain policy governing provider verification levels and compatibility promotion.
 *
 * <p>Architectural Invariants (Contract Tests 45, 46, 47, 48):
 * <ul>
 *   <li>Passing against an emulator/mock server can NEVER upgrade AWS S3 or Cloudflare R2 to COMPLIANCE_VERIFIED.</li>
 *   <li>AWS S3 and Cloudflare R2 compliance requires verified suite execution against real provider endpoints.</li>
 *   <li>Under strict verification mode, unverified or emulator-only production targets fail closed.</li>
 * </ul>
 */
public final class ProviderVerificationPolicy {

    private ProviderVerificationPolicy() {}

    /**
     * Determines the resulting verification status based on test execution evidence.
     *
     * @param target provider target classification
     * @param emulatorRunPassed whether generic emulator/mock suite passed
     * @param realProviderSuitePassed whether dedicated live provider suite passed
     * @return resulting verified status
     */
    public static ProviderVerificationStatus promote(
            S3ProviderTarget target, boolean emulatorRunPassed, boolean realProviderSuitePassed) {
        Objects.requireNonNull(target, "target must not be null");

        if (realProviderSuitePassed) {
            return ProviderVerificationStatus.COMPLIANCE_VERIFIED;
        }

        if (emulatorRunPassed) {
            // Emulators only grant EMULATOR_VERIFIED.
            // Invariant: Emulators CANNOT promote AWS S3 or Cloudflare R2 to COMPLIANCE_VERIFIED!
            return ProviderVerificationStatus.EMULATOR_VERIFIED;
        }

        return ProviderVerificationStatus.UNVERIFIED;
    }

    /**
     * Asserts that the configuration meets strict verification requirements if enabled.
     *
     * @param config storage configuration
     * @throws StorageProviderUnverifiedException if strict mode is active and provider is unverified
     */
    public static void validateVerificationStatus(S3StorageConfiguration config) {
        Objects.requireNonNull(config, "config must not be null");

        if (!config.strictVerificationRequired()) {
            return;
        }

        ProviderVerificationStatus status = config.verificationStatus();
        S3ProviderTarget target = config.providerTarget();

        if (target == S3ProviderTarget.AWS_S3 || target == S3ProviderTarget.CLOUDFLARE_R2) {
            if (status != ProviderVerificationStatus.COMPLIANCE_VERIFIED) {
                throw new StorageProviderUnverifiedException("Provider target " + target
                        + " requires COMPLIANCE_VERIFIED status under strict verification mode, but current status is "
                        + status);
            }
        } else {
            // Generic S3 requires at least EMULATOR_VERIFIED or COMPLIANCE_VERIFIED
            if (status == ProviderVerificationStatus.UNVERIFIED) {
                throw new StorageProviderUnverifiedException("Provider target " + target
                        + " requires at least EMULATOR_VERIFIED status under strict verification mode, but current status is "
                        + status);
            }
        }
    }
}
