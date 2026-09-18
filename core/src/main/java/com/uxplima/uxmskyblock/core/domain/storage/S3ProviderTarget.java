package com.uxplima.uxmskyblock.core.domain.storage;

/**
 * Target S3-compatible provider classification.
 */
public enum S3ProviderTarget {
    /**
     * Official Amazon Web Services Simple Storage Service (AWS S3).
     */
    AWS_S3,

    /**
     * Cloudflare R2 Object Storage.
     */
    CLOUDFLARE_R2,

    /**
     * Generic S3-compatible storage backend (MinIO, Ceph, Wasabi, etc.).
     */
    GENERIC_S3
}
