package com.uxplima.uxmskyblock.core.domain.inactivity;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Summary aggregate report of an inactivity scanning cycle across a batch of islands.
 */
public record IslandInactivityScanReport(
        Instant scannedAt,
        int totalEvaluated,
        int successionsExecuted,
        int islandsArchived,
        int islandsDeleted,
        int islandsSkipped,
        List<IslandSuccessionRecord> records) {

    public IslandInactivityScanReport {
        Objects.requireNonNull(scannedAt, "scannedAt must not be null");
        Objects.requireNonNull(records, "records must not be null");
        records = Collections.unmodifiableList(List.copyOf(records));
    }

    public static IslandInactivityScanReport empty(Instant scannedAt) {
        return new IslandInactivityScanReport(scannedAt, 0, 0, 0, 0, 0, List.of());
    }
}
