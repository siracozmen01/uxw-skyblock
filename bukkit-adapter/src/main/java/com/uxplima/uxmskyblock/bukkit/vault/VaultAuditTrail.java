package com.uxplima.uxmskyblock.bukkit.vault;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.vault.IslandVaultWindow.VaultHolder;
import com.uxplima.uxmskyblock.core.domain.vault.VaultActionType;
import com.uxplima.uxmskyblock.core.domain.vault.VaultAuditLogEntry;
import org.jspecify.annotations.Nullable;

/** The audit entries a closing vault window writes with its page. */
final class VaultAuditTrail {

    private VaultAuditTrail() {}

    /**
     * What the player moved, slot by slot, as audit entries.
     *
     * <p>The vault has held a security audit table, a port and a service call since the vault work,
     * and nothing ever wrote a row: the window passed an empty list at every commit. A shared chest
     * several island members can reach is exactly the thing an owner needs a record of.
     *
     * <p>A slot whose item changed outright is two entries, one out and one in, because that is what
     * happened. A slot that only changed amount is the one entry for the difference.
     */
    static List<VaultAuditLogEntry> of(VaultHolder holder, ItemStack[] atClose) {
        List<ItemStack> openedWith = holder.openedWith();
        List<VaultAuditLogEntry> trail = new ArrayList<>();
        int slots = Math.max(openedWith.size(), atClose.length);
        for (int slot = 0; slot < slots; slot++) {
            ItemStack before = somethingOrNothing(slot < openedWith.size() ? openedWith.get(slot) : null);
            ItemStack after = somethingOrNothing(slot < atClose.length ? atClose[slot] : null);

            if (before != null && after != null && before.isSimilar(after)) {
                int moved = after.getAmount() - before.getAmount();
                if (moved > 0) {
                    trail.add(entry(holder, slot, VaultActionType.DEPOSIT, after, moved));
                } else if (moved < 0) {
                    trail.add(entry(holder, slot, VaultActionType.WITHDRAW, before, -moved));
                }
                continue;
            }
            if (before != null) {
                trail.add(entry(holder, slot, VaultActionType.WITHDRAW, before, before.getAmount()));
            }
            if (after != null) {
                trail.add(entry(holder, slot, VaultActionType.DEPOSIT, after, after.getAmount()));
            }
        }
        return List.copyOf(trail);
    }

    /** An empty slot and an air stack are the same thing here: nothing. */
    private static @Nullable ItemStack somethingOrNothing(@Nullable ItemStack stack) {
        return stack == null || stack.getType().isAir() ? null : stack;
    }

    private static VaultAuditLogEntry entry(
            VaultHolder holder, int slot, VaultActionType action, ItemStack stack, int quantity) {
        String summary = stack.getType().name();
        return VaultAuditLogEntry.create(
                holder.islandId(),
                holder.page(),
                holder.profileId().toString(),
                action,
                slot,
                summary.length() > 128 ? summary.substring(0, 128) : summary,
                quantity);
    }
}
