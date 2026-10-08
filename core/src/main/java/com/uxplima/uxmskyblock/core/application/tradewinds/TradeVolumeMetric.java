package com.uxplima.uxmskyblock.core.application.tradewinds;

import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.api.NamespacedId;
import com.uxplima.uxmskyblock.api.leaderboard.LeaderboardMetricProvider;
import com.uxplima.uxmskyblock.api.leaderboard.MetricConsistency;
import com.uxplima.uxmskyblock.api.leaderboard.MetricReading;
import com.uxplima.uxmskyblock.api.leaderboard.SortDirection;

/** The TradeWinds vessels ranked by the trade they have done at the ports' markets. */
public final class TradeVolumeMetric implements LeaderboardMetricProvider {

    public static final NamespacedId ID = NamespacedId.of("uxm:tradewinds_trade");

    private final MarketOrdersPort orders;

    public TradeVolumeMetric(MarketOrdersPort orders) {
        this.orders = Objects.requireNonNull(orders, "orders must not be null");
    }

    @Override
    public NamespacedId metricId() {
        return ID;
    }

    @Override
    public String displayName() {
        return "TradeWinds trade";
    }

    @Override
    public String owner() {
        return "tradewinds market orders";
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
        return orders.mostTraded(limit).stream()
                .map(traded -> new MetricReading(traded.vessel().value().toString(), traded.name(), traded.volume()))
                .toList();
    }
}
