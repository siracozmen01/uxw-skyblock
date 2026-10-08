package com.uxplima.uxmskyblock.bukkit.config;

import java.util.Locale;
import java.util.Objects;
import java.util.logging.Logger;

import org.bukkit.Material;

import org.spongepowered.configurate.ConfigurationNode;

/**
 * Trading between players, as {@code modules/trade.conf} writes it.
 *
 * @param enabled whether players can trade at all
 * @param requestSeconds how long a request to trade waits for an answer
 * @param maxDistance how far apart two players may be to trade, in blocks; below zero for anywhere on
 *     this server, in any world
 * @param window what the trade window is drawn with
 */
public record TradeConfiguration(boolean enabled, int requestSeconds, int maxDistance, Window window) {

    private static final Logger LOGGER = Logger.getLogger(TradeConfiguration.class.getName());

    public TradeConfiguration {
        Objects.requireNonNull(window, "window must not be null");
        if (requestSeconds < 5 || requestSeconds > 600) {
            throw new IllegalArgumentException("a request waits from 5 to 600 seconds");
        }
    }

    /**
     * What the trade window is drawn with.
     *
     * @param divider the column between the two offers
     * @param ready the button a player presses when they agree
     * @param waiting the same button while they do not
     * @param otherReady what shows that the other player agrees
     * @param otherWaiting what shows that they do not yet
     * @param cancel the button that ends the trade
     */
    public record Window(
            Material divider,
            Material ready,
            Material waiting,
            Material otherReady,
            Material otherWaiting,
            Material cancel) {

        public static final Window SHIPPED = new Window(
                Material.BLACK_STAINED_GLASS_PANE,
                Material.LIME_CONCRETE,
                Material.RED_CONCRETE,
                Material.LIME_STAINED_GLASS_PANE,
                Material.RED_STAINED_GLASS_PANE,
                Material.BARRIER);

        public Window {
            for (Material material : new Material[] {divider, ready, waiting, otherReady, otherWaiting, cancel}) {
                Objects.requireNonNull(material, "a window item must not be null");
                if (material.isAir()) {
                    throw new IllegalArgumentException("a window item is not air");
                }
            }
        }
    }

    public static TradeConfiguration defaultConfiguration() {
        return new TradeConfiguration(true, 60, -1, Window.SHIPPED);
    }

    public static TradeConfiguration load(ConfigurationNode root) {
        TradeConfiguration shipped = defaultConfiguration();
        ConfigurationNode written = root.node("window");
        Window window;
        try {
            window = new Window(
                    material(written, "divider", Window.SHIPPED.divider()),
                    material(written, "ready", Window.SHIPPED.ready()),
                    material(written, "waiting", Window.SHIPPED.waiting()),
                    material(written, "other-ready", Window.SHIPPED.otherReady()),
                    material(written, "other-waiting", Window.SHIPPED.otherWaiting()),
                    material(written, "cancel", Window.SHIPPED.cancel()));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(() -> "modules/trade.conf window: " + e.getMessage() + ". The shipped window is used.");
            window = Window.SHIPPED;
        }
        int seconds = root.node("request-seconds").getInt(shipped.requestSeconds());
        if (seconds < 5 || seconds > 600) {
            LOGGER.warning(() -> "modules/trade.conf request-seconds is from 5 to 600. The shipped "
                    + shipped.requestSeconds() + " is used.");
            seconds = shipped.requestSeconds();
        }
        return new TradeConfiguration(
                root.node("enabled").getBoolean(shipped.enabled()),
                seconds,
                root.node("max-distance").getInt(shipped.maxDistance()),
                window);
    }

    private static Material material(ConfigurationNode section, String key, Material shipped) {
        String name = section.node(key).getString();
        if (name == null || name.isBlank()) {
            return shipped;
        }
        Material material = Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT));
        if (material == null) {
            throw new IllegalArgumentException(key + " names no material: " + name);
        }
        return material;
    }
}
