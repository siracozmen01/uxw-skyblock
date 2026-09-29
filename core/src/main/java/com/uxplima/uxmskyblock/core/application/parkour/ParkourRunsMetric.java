package com.uxplima.uxmskyblock.core.application.parkour;

import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.api.NamespacedId;
import com.uxplima.uxmskyblock.api.leaderboard.LeaderboardMetricProvider;
import com.uxplima.uxmskyblock.api.leaderboard.MetricConsistency;
import com.uxplima.uxmskyblock.api.leaderboard.MetricReading;
import com.uxplima.uxmskyblock.api.leaderboard.SortDirection;

/** The Parkour courses ranked by how many runs have been finished on them, by everybody. */
public final class ParkourRunsMetric implements LeaderboardMetricProvider {

    public static final NamespacedId ID = NamespacedId.of("uxm:parkour_runs");

    private final ParkourService service;

    public ParkourRunsMetric(ParkourService service) {
        this.service = Objects.requireNonNull(service, "service must not be null");
    }

    @Override
    public NamespacedId metricId() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Parkour runs";
    }

    @Override
    public String owner() {
        return "parkour records";
    }

    @Override
    public String rootType() {
        return "ISLAND";
    }

    @Override
    public SortDirection sortDirection() {
        return SortDirection.HIGHEST_FIRST;
    }

    @Override
    public MetricConsistency consistency() {
        return MetricConsistency.EVENT_DRIVEN_EXACT;
    }

    @Override
    public List<MetricReading> read(int limit) {
        return service.mostRun(limit).stream()
                .map(runs -> new MetricReading(runs.course().value().toString(), runs.name(), runs.runs()))
                .toList();
    }
}
