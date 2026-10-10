/**
 * Where a player arrives, on a server that tells and on one that does not.
 *
 * <p>Paper fires a teleport event before a player is moved, so a rule can refuse the move. Folia fires
 * none for an asynchronous teleport, a command teleport, an ender pearl or a respawn, so every rule that
 * listened for one was silent there. {@link com.uxplima.uxmskyblock.bukkit.arrival.ArrivalWatch} hears
 * both and hands every arrival to the same rules.
 */
@NullMarked
package com.uxplima.uxmskyblock.bukkit.arrival;

import org.jspecify.annotations.NullMarked;
