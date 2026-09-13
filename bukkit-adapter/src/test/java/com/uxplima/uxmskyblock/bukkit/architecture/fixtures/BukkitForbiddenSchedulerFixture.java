package com.uxplima.uxmskyblock.bukkit.architecture.fixtures;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitScheduler;

public final class BukkitForbiddenSchedulerFixture {

    public BukkitScheduler callScheduler() {
        return Bukkit.getScheduler();
    }
}
