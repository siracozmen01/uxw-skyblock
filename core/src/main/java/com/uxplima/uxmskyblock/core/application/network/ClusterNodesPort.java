package com.uxplima.uxmskyblock.core.application.network;

import java.time.Duration;
import java.util.List;

import com.uxplima.uxmskyblock.core.domain.network.NodeHealth;

/** Where each node says it is alive, which world it serves and how loaded it is. */
public interface ClusterNodesPort {

    /** Records this node's health now, on the database's clock. */
    void publish(NodeHealth health, String worldName);

    /** The nodes serving this world that were heard from within {@code window}, each marked active. */
    List<NodeHealth> serving(String worldName, Duration window);
}
