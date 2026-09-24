package com.uxplima.uxmskyblock.core.application.storage;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.uxplima.uxmskyblock.core.domain.storage.ObjectStorageCapability;
import com.uxplima.uxmskyblock.core.domain.storage.ObjectStorageProviderId;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import com.uxplima.uxmskyblock.core.domain.storage.StorageObjectMetadata;
import org.jspecify.annotations.Nullable;

/**
 * Several storage destinations answering as one, for the {@code MIRRORED} storage policy.
 *
 * <p>The persistence specification publishes three policies, and only one destination could ever be
 * configured: a server whose bucket was lost had lost its backups with it. A mirror writes every object
 * to every destination and reads each one from the first destination that still has it, so a backup
 * survives the loss of either copy.
 *
 * <p>No write spans the destinations atomically, because nothing can make a local folder and a remote
 * bucket agree in one step. A write is tried on every destination and fails when any of them failed,
 * after the others were written. The backup service does not write through this at all: it takes the
 * {@link #destinations()} and tracks each one's publication, which is what lets it tell a backup that
 * reached one destination from one that reached none.
 */
public final class MirroredObjectStorage implements ObjectStoragePort, AutoCloseable {

    private final List<ObjectStoragePort> destinations;

    public MirroredObjectStorage(List<ObjectStoragePort> destinations) {
        Objects.requireNonNull(destinations, "destinations must not be null");
        if (destinations.size() < 2) {
            throw new IllegalArgumentException("A mirror needs at least two destinations");
        }
        this.destinations = List.copyOf(destinations);
    }

    /** The destinations a backup is published to: every mirrored one, or the storage itself. */
    public static List<ObjectStoragePort> destinationsOf(ObjectStoragePort storage) {
        Objects.requireNonNull(storage, "storage must not be null");
        return storage instanceof MirroredObjectStorage mirror ? mirror.destinations() : List.of(storage);
    }

    public List<ObjectStoragePort> destinations() {
        return destinations;
    }

    @Override
    public void putObject(StorageBucket bucket, String objectKey, byte[] data, StorageObjectMetadata metadata) {
        everywhere(objectKey, destination -> destination.putObject(bucket, objectKey, data, metadata));
    }

    @Override
    public Optional<byte[]> getObject(StorageBucket bucket, String objectKey) {
        for (ObjectStoragePort destination : destinations) {
            Optional<byte[]> found = readQuietly(() -> destination.getObject(bucket, objectKey));
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean exists(StorageBucket bucket, String objectKey) {
        for (ObjectStoragePort destination : destinations) {
            if (readQuietly(() -> Optional.of(destination.exists(bucket, objectKey)))
                    .orElse(false)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void deleteObject(StorageBucket bucket, String objectKey) {
        everywhere(objectKey, destination -> destination.deleteObject(bucket, objectKey));
    }

    @Override
    public List<String> listObjects(StorageBucket bucket, String prefix) {
        Set<String> keys = new LinkedHashSet<>();
        for (ObjectStoragePort destination : destinations) {
            readQuietly(() -> Optional.of(destination.listObjects(bucket, prefix)))
                    .ifPresent(keys::addAll);
        }
        return new ArrayList<>(keys);
    }

    @Override
    public Optional<StorageObjectMetadata> getMetadata(StorageBucket bucket, String objectKey) {
        for (ObjectStoragePort destination : destinations) {
            Optional<StorageObjectMetadata> found = readQuietly(() -> destination.getMetadata(bucket, objectKey));
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /** What every destination can do, since a request may land on any of them. */
    @Override
    public Set<ObjectStorageCapability> capabilities() {
        Set<ObjectStorageCapability> shared = EnumSet.allOf(ObjectStorageCapability.class);
        for (ObjectStoragePort destination : destinations) {
            shared.retainAll(destination.capabilities());
        }
        return Set.copyOf(shared);
    }

    @Override
    public ObjectStorageProviderId providerId() {
        return ObjectStorageProviderId.of("mirrored");
    }

    /**
     * Runs {@code write} on every destination, and fails afterwards when any of them failed.
     *
     * <p>Stopping at the first failure would leave the destinations after it without the object, when
     * they were the copies still working.
     */
    private void everywhere(String objectKey, java.util.function.Consumer<ObjectStoragePort> write) {
        @Nullable RuntimeException first = null;
        for (ObjectStoragePort destination : destinations) {
            try {
                write.accept(destination);
            } catch (RuntimeException failed) {
                if (first == null) {
                    first = failed;
                } else {
                    first.addSuppressed(failed);
                }
            }
        }
        if (first != null) {
            throw new IllegalStateException("A mirrored destination refused " + objectKey, first);
        }
    }

    /** Closes every destination that holds something to close, such as a remote client. */
    @Override
    public void close() {
        IllegalStateException failure = null;
        for (ObjectStoragePort destination : destinations) {
            if (destination instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    failure = withCause(failure, interrupted);
                } catch (Exception failed) {
                    failure = withCause(failure, failed);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static IllegalStateException withCause(@Nullable IllegalStateException failure, Exception cause) {
        if (failure == null) {
            return new IllegalStateException("A mirrored destination did not close", cause);
        }
        failure.addSuppressed(cause);
        return failure;
    }

    /** A read from one destination, where a destination that cannot answer is one that has nothing. */
    private static <T> Optional<T> readQuietly(java.util.function.Supplier<Optional<T>> read) {
        try {
            return read.get();
        } catch (RuntimeException unreachable) {
            return Optional.empty();
        }
    }
}
