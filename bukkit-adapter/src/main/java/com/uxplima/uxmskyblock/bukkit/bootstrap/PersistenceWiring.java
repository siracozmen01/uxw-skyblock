package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.application.storage.ObjectStoragePort;
import com.uxplima.uxmskyblock.core.domain.storage.ProviderVerificationStatus;
import com.uxplima.uxmskyblock.core.domain.storage.S3AddressingMode;
import com.uxplima.uxmskyblock.core.domain.storage.S3Credentials;
import com.uxplima.uxmskyblock.core.domain.storage.S3ProviderTarget;
import com.uxplima.uxmskyblock.core.domain.storage.S3StorageConfiguration;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import com.uxplima.uxmskyblock.persistence.storage.LocalFilesystemStorageAdapter;
import com.uxplima.uxmskyblock.persistence.storage.s3.S3ObjectStorageAdapter;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Encapsulates persistence layer initialization, connection pool lifecycle,
 * database driver selection, and object storage engine resolution (Local/S3/R2).
 */
public final class PersistenceWiring implements AutoCloseable {

    /** The bucket a backup is written to when the operator names none. */
    public static final String DEFAULT_BACKUP_BUCKET = "uxmskyblock-backups";

    private final PersistenceBootstrap persistenceBootstrap;
    private final ObjectStoragePort objectStoragePort;
    private final StorageBucket backupBucket;

    public PersistenceWiring(
            PersistenceBootstrap persistenceBootstrap,
            ObjectStoragePort objectStoragePort,
            StorageBucket backupBucket) {
        this.persistenceBootstrap =
                Objects.requireNonNull(persistenceBootstrap, "persistenceBootstrap must not be null");
        this.objectStoragePort = Objects.requireNonNull(objectStoragePort, "objectStoragePort must not be null");
        this.backupBucket = Objects.requireNonNull(backupBucket, "backupBucket must not be null");
    }

    public PersistenceWiring(PersistenceBootstrap persistenceBootstrap, ObjectStoragePort objectStoragePort) {
        this(persistenceBootstrap, objectStoragePort, new StorageBucket(DEFAULT_BACKUP_BUCKET));
    }

    public PersistenceWiring(PersistenceBootstrap persistenceBootstrap) {
        this(persistenceBootstrap, new LocalFilesystemStorageAdapter(Path.of("backups")));
    }

    /**
     * The bucket backups are written to and read back from.
     *
     * <p>The restore command used to write this name into its own source, so an operator who
     * renamed their bucket wrote backups to one place and restored from another that does not
     * exist. The restore answered "manifest missing" and said nothing about why.
     */
    public StorageBucket backupBucket() {
        return backupBucket;
    }

    /**
     * Resolves the database backend and object storage engine from configuration or environment overrides.
     */
    public static PersistenceWiring resolve(@Nullable ConfigurationNode rootNode, Path dataDir) {
        PersistenceBootstrap persistenceBootstrap;
        String envJdbc = System.getProperty("skyblock.jdbc.url", System.getenv("SKYBLOCK_JDBC_URL"));
        String envUser = System.getProperty("skyblock.db.user", System.getenv("SKYBLOCK_DB_USER"));
        String envPass = System.getProperty("skyblock.db.password", System.getenv("SKYBLOCK_DB_PASSWORD"));

        if (envJdbc != null && !envJdbc.isBlank()) {
            persistenceBootstrap = PersistenceBootstrap.createRemote(envJdbc.trim(), envUser, envPass, 10);
        } else if (rootNode != null && isRemoteConfigured(rootNode)) {
            ConfigurationNode dbNode = rootNode.node("database");
            String jdbcUrl = dbNode.node("jdbc-url").getString();
            String user = dbNode.node("username").getString("");
            String pass = dbNode.node("password").getString("");
            int poolSize = dbNode.node("max-pool-size").getInt(10);
            persistenceBootstrap = PersistenceBootstrap.createRemote(jdbcUrl.trim(), user, pass, poolSize);
        } else {
            Path dbFile = dataDir.resolve("skyblock.db");
            persistenceBootstrap = PersistenceBootstrap.createSqlite(dbFile);
        }

        // The bucket is named once, whichever storage backend is chosen, because a backup written
        // under one name has to be read back under the same one.
        String configuredBucket =
                rootNode != null ? rootNode.node("storage", "s3", "bucket").getString() : null;
        if (configuredBucket == null || configuredBucket.isBlank()) {
            configuredBucket = System.getProperty("skyblock.s3.bucket", System.getenv("SKYBLOCK_S3_BUCKET"));
        }
        if (configuredBucket == null || configuredBucket.isBlank()) {
            configuredBucket = DEFAULT_BACKUP_BUCKET;
        }
        StorageBucket backupBucket = new StorageBucket(configuredBucket);

        // Resolve ObjectStoragePort (S3 or local filesystem)
        ObjectStoragePort objectStorage;
        String storageType = null;
        if (rootNode != null) {
            storageType = rootNode.node("storage", "type").getString();
        }
        if (storageType == null || storageType.isBlank()) {
            storageType = System.getProperty("skyblock.storage.type", System.getenv("SKYBLOCK_STORAGE_TYPE"));
        }

        if ("s3".equalsIgnoreCase(storageType) || "r2".equalsIgnoreCase(storageType)) {
            String endpointStr = rootNode != null
                    ? rootNode.node("storage", "s3", "endpoint").getString()
                    : null;
            if (endpointStr == null || endpointStr.isBlank()) {
                endpointStr = System.getProperty("skyblock.s3.endpoint", System.getenv("SKYBLOCK_S3_ENDPOINT"));
            }
            if (endpointStr == null || endpointStr.isBlank()) {
                endpointStr = "https://s3.amazonaws.com";
            }

            String region =
                    rootNode != null ? rootNode.node("storage", "s3", "region").getString() : null;
            if (region == null || region.isBlank()) {
                region = System.getProperty("skyblock.s3.region", System.getenv("SKYBLOCK_S3_REGION"));
            }
            if (region == null || region.isBlank()) {
                region = "us-east-1";
            }

            String accessKey = rootNode != null
                    ? rootNode.node("storage", "s3", "access-key").getString()
                    : null;
            if (accessKey == null || accessKey.isBlank()) {
                accessKey = System.getProperty("skyblock.s3.access.key", System.getenv("SKYBLOCK_S3_ACCESS_KEY"));
            }
            if (accessKey == null || accessKey.isBlank()) {
                accessKey = "anonymous";
            }

            String secretKey = rootNode != null
                    ? rootNode.node("storage", "s3", "secret-key").getString()
                    : null;
            if (secretKey == null || secretKey.isBlank()) {
                secretKey = System.getProperty("skyblock.s3.secret.key", System.getenv("SKYBLOCK_S3_SECRET_KEY"));
            }
            if (secretKey == null || secretKey.isBlank()) {
                secretKey = "anonymous";
            }

            String sessionToken = rootNode != null
                    ? rootNode.node("storage", "s3", "session-token").getString()
                    : null;
            if (sessionToken == null || sessionToken.isBlank()) {
                sessionToken =
                        System.getProperty("skyblock.s3.session.token", System.getenv("SKYBLOCK_S3_SESSION_TOKEN"));
            }
            if (sessionToken != null && sessionToken.isBlank()) {
                sessionToken = null;
            }

            S3Credentials credentials = S3Credentials.of(accessKey, secretKey, sessionToken);

            String rawAddressing = rootNode != null
                    ? rootNode.node("storage", "s3", "addressing-mode").getString("PATH_STYLE")
                    : "PATH_STYLE";
            S3AddressingMode addressingMode = "VIRTUAL_HOSTED".equalsIgnoreCase(rawAddressing)
                    ? S3AddressingMode.VIRTUAL_HOSTED
                    : S3AddressingMode.PATH_STYLE;

            String rawTarget = rootNode != null
                    ? rootNode.node("storage", "s3", "provider-target").getString("GENERIC_S3")
                    : "GENERIC_S3";
            S3ProviderTarget providerTarget = "AWS".equalsIgnoreCase(rawTarget) || "AWS_S3".equalsIgnoreCase(rawTarget)
                    ? S3ProviderTarget.AWS_S3
                    : ("CLOUDFLARE_R2".equalsIgnoreCase(rawTarget) || "R2".equalsIgnoreCase(rawTarget)
                            ? S3ProviderTarget.CLOUDFLARE_R2
                            : S3ProviderTarget.GENERIC_S3);

            S3StorageConfiguration s3Config = new S3StorageConfiguration(
                    URI.create(endpointStr),
                    region,
                    backupBucket,
                    credentials,
                    addressingMode,
                    providerTarget,
                    null,
                    S3StorageConfiguration.DEFAULT_MULTIPART_THRESHOLD,
                    S3StorageConfiguration.DEFAULT_PART_SIZE,
                    S3StorageConfiguration.DEFAULT_MAX_RETRIES,
                    false,
                    ProviderVerificationStatus.EMULATOR_VERIFIED);

            objectStorage = new S3ObjectStorageAdapter(s3Config);
        } else {
            String localPathStr =
                    rootNode != null ? rootNode.node("storage", "local-path").getString() : null;
            Path backupDir = (localPathStr != null && !localPathStr.isBlank())
                    ? dataDir.resolve(localPathStr.trim())
                    : dataDir.resolve("backups");
            objectStorage = new LocalFilesystemStorageAdapter(backupDir);
        }

        return new PersistenceWiring(persistenceBootstrap, objectStorage, backupBucket);
    }

    private static boolean isRemoteConfigured(ConfigurationNode rootNode) {
        ConfigurationNode dbNode = rootNode.node("database");
        String dbType = dbNode.node("type").getString("sqlite");
        if ("remote".equalsIgnoreCase(dbType)
                || "mysql".equalsIgnoreCase(dbType)
                || "postgresql".equalsIgnoreCase(dbType)) {
            String jdbcUrl = dbNode.node("jdbc-url").getString();
            return jdbcUrl != null && !jdbcUrl.isBlank();
        }
        return false;
    }

    public PersistenceBootstrap bootstrap() {
        return persistenceBootstrap;
    }

    public ObjectStoragePort objectStoragePort() {
        return objectStoragePort;
    }

    public ObjectStoragePort storagePort() {
        return objectStoragePort;
    }

    @Override
    @SuppressWarnings("EmptyCatch")
    public void close() {
        persistenceBootstrap.close();
        if (objectStoragePort instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }
}
