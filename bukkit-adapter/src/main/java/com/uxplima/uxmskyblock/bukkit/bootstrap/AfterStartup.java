package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.Plugin;

/**
 * Runs the part of enabling that needs the worlds and the other plugins, once they are there.
 *
 * <p>The plugin loads at startup, before the worlds, because that is the only way the default world
 * can be given the island generator. At that moment no world exists and floodgate, Vault,
 * PlaceholderAPI, mcMMO and the web maps have not enabled. Whatever asks for them waits for the
 * server to finish loading. A server that has already loaded, after a reload or in a test, runs it
 * at once.
 */
final class AfterStartup implements Listener {

    private final Runnable work;
    private final AtomicBoolean ran = new AtomicBoolean();

    private AfterStartup(Runnable work) {
        this.work = work;
    }

    /** Runs the work now if the server has loaded its worlds, or as soon as it has. */
    static void run(Plugin plugin, Runnable work) {
        Objects.requireNonNull(plugin, "plugin must not be null");
        Objects.requireNonNull(work, "work must not be null");
        if (!plugin.getServer().getWorlds().isEmpty()) {
            work.run();
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(new AfterStartup(work), plugin);
    }

    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        if (ran.compareAndSet(false, true)) {
            HandlerList.unregisterAll(this);
            work.run();
        }
    }
}
