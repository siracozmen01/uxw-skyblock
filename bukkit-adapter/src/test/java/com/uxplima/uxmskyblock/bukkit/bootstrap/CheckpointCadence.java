package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import org.bukkit.plugin.Plugin;

import com.uxplima.uxmskyblock.bukkit.config.PlayerStateConfigurationAdapter;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.scheduler.FoliaSchedulerAdapter;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.durability.PlayerStateDurabilityConfig;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * The session subsystem wired the way the plugin wires it, on a scheduler that remembers how often
 * each repeating task was asked to run.
 */
final class CheckpointCadence implements AutoCloseable {

    /** How often the session lease is renewed, which is not the checkpoint and must not be taken for it. */
    static final Duration HEARTBEAT = Duration.ofSeconds(5);

    final List<Duration> periods = new CopyOnWriteArrayList<>();
    final AuthorityWiring wiring;
    private final PersistenceBootstrap persistence;

    CheckpointCadence(Plugin plugin, Path databaseFile, PlayerStateDurabilityConfig config) {
        persistence = PersistenceBootstrap.createSqlite(databaseFile);
        SchedulerPort scheduler = new Recording(new FoliaSchedulerAdapter(plugin), periods);
        wiring = AuthorityWiring.create(
                ServerNodeId.of("cadence-node"),
                config,
                persistence,
                scheduler,
                new IslandProtectionListener(persistence.islandStoragePort(), new IslandAccessService()),
                Messages.bundled());
    }

    /** The player-state policy the shipped {@code config.conf} gives. */
    static PlayerStateDurabilityConfig shipped() throws Exception {
        try (var in = Objects.requireNonNull(
                CheckpointCadence.class.getResourceAsStream("/config.conf"), "config.conf ships with the plugin")) {
            return written(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /** The player-state policy an operator wrote. */
    static PlayerStateDurabilityConfig written(String hocon) throws Exception {
        return PlayerStateConfigurationAdapter.load(HoconConfigurationLoader.builder()
                .source(() -> new BufferedReader(new StringReader(hocon)))
                .build()
                .load());
    }

    @Override
    public void close() {
        wiring.close();
        persistence.close();
    }

    /** Runs everything on the real scheduler and writes down every repeating period it was given. */
    private record Recording(SchedulerPort real, List<Duration> periods) implements SchedulerPort {

        @Override
        public void onGlobal(Runnable task) {
            real.onGlobal(task);
        }

        @Override
        public void onRegion(String worldName, int chunkX, int chunkZ, Runnable task) {
            real.onRegion(worldName, chunkX, chunkZ, task);
        }

        @Override
        public void onEntity(PlayerUuid playerUuid, Runnable task) {
            real.onEntity(playerUuid, task);
        }

        @Override
        public void onEntity(PlayerUuid playerUuid, Runnable task, Runnable retired) {
            real.onEntity(playerUuid, task, retired);
        }

        @Override
        public boolean ownsEntity(PlayerUuid playerUuid) {
            return real.ownsEntity(playerUuid);
        }

        @Override
        public void async(Runnable task) {
            real.async(task);
        }

        @Override
        public void asyncAfter(Duration delay, Runnable task) {
            real.asyncAfter(delay, task);
        }

        @Override
        public void laterGlobal(Duration delay, Runnable task) {
            real.laterGlobal(delay, task);
        }

        @Override
        public AutoCloseable repeatGlobal(Runnable task, Duration initialDelay, Duration period) {
            periods.add(period);
            return real.repeatGlobal(task, initialDelay, period);
        }

        @Override
        public AutoCloseable repeatAsync(Runnable task, Duration initialDelay, Duration period) {
            periods.add(period);
            return real.repeatAsync(task, initialDelay, period);
        }
    }
}
