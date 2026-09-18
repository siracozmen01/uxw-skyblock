package com.uxplima.uxmskyblock.core.domain.storage;

/**
 * URL addressing mode for S3-compatible endpoints.
 */
public enum S3AddressingMode {
    /**
     * Path-style addressing: {@code https://endpoint/bucket/key}
     */
    PATH_STYLE,

    /**
     * Virtual-hosted style addressing: {@code https://bucket.endpoint/key}
     */
    VIRTUAL_HOSTED
}
