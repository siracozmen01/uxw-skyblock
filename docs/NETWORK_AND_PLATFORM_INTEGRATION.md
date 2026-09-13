# Network & Platform Integration: Multi-Server, Redis, Velocity, Folia & Bedrock

This specification outlines horizontal cluster scaling, the dual-tier Redis bus (Streams & Pub/Sub), Velocity proxy routing, Folia multi-threading, and native Bedrock form integration.

---

## 1. Network Topology & Horizontal Scaling

For massive networks, islands are distributed across multiple backend Folia server nodes to eliminate single-JVM CPU and memory bottlenecks:

```
                                +-------------------+
                                |  Velocity Proxy   |
                                |  (BusBroker Jar)  |
                                +---------+---------+
                                          |
                        +-----------------+-----------------+
                        |                 |                 |
                        v                 v                 v
                +---------------+ +---------------+ +---------------+
                | Skyblock-01   | | Skyblock-02   | | Skyblock-03   |
                | (Folia Node)  | | (Folia Node)  | | (Folia Node)  |
                +-------+-------+ +-------+-------+ +-------+-------+
                        |                 |                 |
                        +--------+--------+--------+--------+
                                 |                 |
                                 v                 v
                        +-----------------+ +-----------------------+
                        | Redis 7.2       | | SQL Cluster           |
                        | Streams & PubSub| | (Sole Source of Truth)|
                        | (Routing/Stream)| | (MySQL / PG / MariaDB)|
                        +-----------------+ +-----------------------+
```

---

## 2. Dual-Tier Distributed Network Bus (`uxmSkyblock-redis`)

* **Companion Plugin Architecture:** The core plugin does not hard-depend on Redis. An SPI (`BusTransportFactory`) in `:core` binds to the `uxmSkyblock-redis` companion jar when present.
* **Dual-Tier Event Transport Strategy:**
  1. **Ephemeral Channel via Redis Pub/Sub:**
     - Used strictly for fire-and-forget, non-critical real-time communications:
       - `IslandChatFrame`: Cross-server private island team communications (`/is chat`).
       - `PresenceHeartbeatFrame`: Online status and staff spy notifications.
       - `CacheInvalidateFrame`: Lightweight hints prompting remote L1 caches to refresh.
     - Packets dropped during transient network blips are acceptable for ephemeral topics.
  2. **Durable State via Transactional Outbox & Redis Streams:**
     - All critical state transitions (bank mutations, role promotions, kicks, upgrades, island deletions) are committed to the SQL `outbox_events` table within the mutating SQL transaction.
     - An asynchronous worker dispatches outbox rows into Redis Streams (`uxmskyblock:stream:domain_events`).
     - **Idempotent / Effectively-Once Consumer Processing (`consumer_inbox`):**
       - Because Redis Streams delivers with at-least-once semantics, consumers maintain an idempotent processing contract:
       - **Local SQL Projections:** Insert into `consumer_inbox (consumer_name, event_id)` and the projection update execute inside the same local SQL transaction. `XACK` is sent to Redis Streams strictly after successful commit. Duplicate deliveries fail the inbox insert and are acknowledged without re-running handlers.
       - **External Side Effects (Discord, Webhooks, Third-Party APIs):** Cannot participate in SQL transactions. Governed by downstream deduplication (passing `event_id`), exponential retry backoff, and Dead Letter Streams (`uxmskyblock:stream:dead_letter`).
* **Single-Writer Authority Leases & Canonical SQL Epoch Fencing:**
  - Distributed mutual exclusion is NOT managed via naked Redis locks.
  - Active islands have their canonical authority state and monotonic `authority_epoch BIGINT` stored in SQL table `island_authorities`.
  - Redis caches the routing directory (`uxmskyblock:route:island:<id> -> server_id`) with a 15-second TTL.
  - Stale authority writes resulting from GC pauses or network partitions are fenced out at the SQL CAS layer. Routine heartbeat renewals by the active owner never advance the epoch.
* **Degradation & Failure Policy on Redis Disconnection:**
  - If Redis connection drops:
    - **Local State Mutations (Safe Operation):** Sub-servers holding valid active leases in SQL `island_authorities` CONTINUE processing local player transactions and island modifications normally, because canonical authority and CAS validation reside entirely in SQL using database clock comparisons.
    - **Chat:** Gracefully falls back to local sub-server scope only.
    - **Leaderboards:** Serves stale cached data from L1 Caffeine.
    - **Cross-Server Teleportation (`/is visit`):** Fails closed with a localized error; players cannot switch nodes while topology is indeterminate.
    - **Outbox Dispatcher:** Outbox events buffer safely in the SQL `outbox_events` table and resume streaming once Redis reconnects.

---

## 3. Velocity Proxy Broker (`uxmSkyblock-velocity`)

* **Proxy-Side Companion:** A lightweight Velocity plugin that intercepts `/island go`, `/is visit`, and cross-server invites.
* **Seamless Player Forwarding:** Queries the distributed island routing table and switches the player's connection to the target server hosting the island before dispatching the local spawn teleport.

---

## 4. Native V1 Folia Concurrency Model

* **Absolute Ban on `BukkitScheduler`:** Checked at compile time and bytecode level via ArchUnit `FoliaThreadingDriftTest`.
* **Hexagonal Scheduler Boundary:** Core/application code uses the platform-neutral Skyblock `SchedulerPort`; the Bukkit adapter implements it using `uxmlib-common`'s Folia-ready `Scheduler`:
  - Islands utilize a 5,120-block isolation heuristic to maximize independent chunk region formation on Folia.
  - Block placement, chest access, entity spawns, and schematic pasting are dispatched via `SchedulerPort.region(worldId, chunkX, chunkZ)`. The Bukkit adapter translates this to `uxmlib-common`'s `scheduler.region(world, chunkX, chunkZ)`.
  - Player inventory, titles, and menus are dispatched via `SchedulerPort.entity(playerRef)`. The Bukkit adapter translates this to `scheduler.entity(player)`.
  - Database queries and external HTTP calls execute on `SchedulerPort.async()`.
  - Global broadcasts and ticks execute on `SchedulerPort.global()`.

---

## 5. Geyser & Floodgate Dual-Platform UI

* **First-Class Bedrock Touch Support:**
  - Bedrock players connecting via Geyser/Floodgate are automatically detected via `uxmlib-bedrock` (`BedrockDetector`).
  - While Java players view custom chest inventory GUIs, Bedrock players are served native **Cumulus Touch Forms**:
    - **SimpleForm:** Main island navigation and public island browser.
    - **ModalForm:** Confirmation dialogs (delete island, kick member, upgrade tier).
    - **CustomForm:** Text inputs for island renaming, warp creation, and permission toggles.
* **Zero UI Desync:** Both platforms interact with the same underlying domain actions, sealed `IslandResult` responses, and permission checks.
