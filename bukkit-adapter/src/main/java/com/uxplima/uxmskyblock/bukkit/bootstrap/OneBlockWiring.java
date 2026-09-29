package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.Objects;
import java.util.SplittableRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.bukkit.config.OneBlockConfiguration;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;

/**
 * The OneBlock game mode's running parts: the service that counts breaks, and the schedule that writes
 * the count down.
 */
public final class OneBlockWiring implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(OneBlockWiring.class.getName());

    private final OneBlockService service;
    private final AutoCloseable saving;

    public OneBlockWiring(OneBlockConfiguration config, PersistenceBootstrap persistence, SchedulerPort scheduler) {
        Objects.requireNonNull(config, "config must not be null");
        this.service = new OneBlockService(persistence.oneBlockProgressPort(), config.phases(), new SplittableRandom());
        this.saving = scheduler.repeatAsync(service::flush, config.saveInterval(), config.saveInterval());
    }

    public OneBlockService service() {
        return service;
    }

    /** Stops the schedule and writes what is still only counted, before the database closes. */
    @Override
    public void close() {
        try {
            saving.close();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Stopping the OneBlock save schedule failed.", e);
        }
        service.flush();
    }
}
