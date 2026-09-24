package com.uxplima.uxmskyblock.bukkit.session;

/**
 * An open window that writes the player's state with its own commit, so the ambient checkpoint waits
 * until it closes.
 *
 * <p>An island vault page is edited in a window and written when the window closes. Items a player
 * took out were in their inventory while the page still held them, and a checkpoint in between wrote
 * the inventory with the items in it: a crash before the window closed left the items in both places.
 * The holder of such a window carries this, and the checkpoint leaves the player alone while it is
 * open; the next checkpoint after it closes writes what they hold.
 */
public interface WritesPlayerStateItself {}
