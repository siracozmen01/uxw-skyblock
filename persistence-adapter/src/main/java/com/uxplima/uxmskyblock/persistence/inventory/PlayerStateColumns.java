package com.uxplima.uxmskyblock.persistence.inventory;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;

/**
 * Everything a player carries between sessions, as the columns of {@code profile_inventories}.
 *
 * <p>The checkpoint and the final write of a session wrote the inventory and nothing else, while a
 * join put every column back on the player. The ender chest, experience, health, hunger and effects
 * a player had were replaced on every join with what the row held when the profile was made: an
 * empty ender chest and no experience. Every write of a player's state writes all of it, through
 * this.
 */
public final class PlayerStateColumns {

    /** The assignments, in the order {@link #bind} fills them. */
    public static final String ASSIGNMENTS =
            "inventory_nbt = ?, enderchest_nbt = ?, experience_points = ?, health = ?, "
                    + "food_level = ?, saturation = ?, active_potion_effects_nbt = ?, logout_world = ?, logout_x = ?, "
                    + "logout_y = ?, logout_z = ?, gamemode = ?, flight_allowed = ?";

    private PlayerStateColumns() {}

    /** Binds {@code state} from parameter {@code first} on, and answers the index after the last. */
    public static int bind(PreparedStatement ps, int first, ProfileInventoryRecord state) throws SQLException {
        int i = first;
        ps.setBytes(i++, state.inventoryNbt());
        ps.setBytes(i++, state.enderchestNbt());
        ps.setInt(i++, state.experiencePoints());
        ps.setDouble(i++, state.health());
        ps.setInt(i++, state.foodLevel());
        ps.setFloat(i++, state.saturation());
        byte[] effects = state.activePotionEffectsNbt();
        if (effects != null) {
            ps.setBytes(i++, effects);
        } else {
            ps.setNull(i++, Types.BINARY);
        }
        String world = state.logoutWorld();
        if (world != null) {
            ps.setString(i++, world);
        } else {
            ps.setNull(i++, Types.VARCHAR);
        }
        i = coordinate(ps, i, state.logoutX());
        i = coordinate(ps, i, state.logoutY());
        i = coordinate(ps, i, state.logoutZ());
        ps.setString(i++, state.gamemode());
        ps.setBoolean(i++, state.flightAllowed());
        return i;
    }

    private static int coordinate(PreparedStatement ps, int i, @org.jspecify.annotations.Nullable Double value)
            throws SQLException {
        if (value != null) {
            ps.setDouble(i, value);
        } else {
            ps.setNull(i, Types.DOUBLE);
        }
        return i + 1;
    }
}
