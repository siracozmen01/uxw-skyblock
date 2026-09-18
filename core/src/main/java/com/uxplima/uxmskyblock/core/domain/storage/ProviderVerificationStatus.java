package com.uxplima.uxmskyblock.core.domain.storage;

/**
 * Compliance and verification status of a storage provider integration.
 *
 * <p>Invariant: Passing against a local emulator or mock server grants {@code EMULATOR_VERIFIED},
 * but strictly CANNOT promote AWS S3 or Cloudflare R2 to {@code COMPLIANCE_VERIFIED}.
 */
public enum ProviderVerificationStatus {
    /**
     * Provider configuration has not been verified against a live endpoint.
     */
    UNVERIFIED,

    /**
     * Verified against a local S3 emulator or compatible mock server.
     * Sufficient only for generic S3 adapter verification.
     */
    EMULATOR_VERIFIED,

    /**
     * Fully verified against a real production provider account/endpoint.
     */
    COMPLIANCE_VERIFIED
}
