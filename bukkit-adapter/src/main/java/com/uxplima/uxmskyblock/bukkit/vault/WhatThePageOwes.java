package com.uxplima.uxmskyblock.bukkit.vault;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import org.jspecify.annotations.Nullable;

/**
 * What an open vault window has moved, against the page as it is stored, and the player as the
 * stored page leaves them.
 *
 * <p>The page is written only when the window closes. Until then the stored page holds what it held
 * at the open, so what the window holds and the stored page does not is what the player put in, and
 * what the stored page holds and the window does not is what the player took out. A refused save puts
 * the player back by this arithmetic, and a checkpoint taken while the window is open writes the player
 * by it: whatever else they gained meanwhile is written, and nothing the page still holds is.
 */
final class WhatThePageOwes {

    /** The slots of a player's inventory that are storage, not armour and not the off hand. */
    static final int STORAGE_SLOTS = 36;

    /** What the player put into the window and what they took out of it, stack by stack. */
    record Moved(List<ItemStack> putIn, List<ItemStack> takenOut) {}

    private WhatThePageOwes() {}

    /** The page as it stood when the window opened, one entry per slot, air where a slot was empty. */
    static List<ItemStack> openedWith(@Nullable ItemStack[] stored, int slots) {
        List<ItemStack> snapshot = new ArrayList<>(slots);
        for (int slot = 0; slot < slots; slot++) {
            ItemStack stack = slot < stored.length ? stored[slot] : null;
            snapshot.add(stack == null ? new ItemStack(Material.AIR) : stack.clone());
        }
        return snapshot;
    }

    /**
     * What {@code stored} holds past its first {@code slots} slots, up to its last stack, air where a
     * slot was empty. Empty when nothing lies past them. A page written when pages were larger keeps
     * this, because writing the page without it deleted it.
     */
    static List<ItemStack> beyond(@Nullable ItemStack[] stored, int slots) {
        int last = -1;
        for (int slot = slots; slot < stored.length; slot++) {
            ItemStack stack = stored[slot];
            if (stack != null && !stack.getType().isAir()) {
                last = slot;
            }
        }
        List<ItemStack> kept = new ArrayList<>();
        for (int slot = slots; slot <= last; slot++) {
            ItemStack stack = stored[slot];
            kept.add(stack == null ? new ItemStack(Material.AIR) : stack.clone());
        }
        return kept;
    }

    /** What the window holding {@code window} has moved since it opened on {@code openedWith}. */
    static Moved between(List<ItemStack> openedWith, @Nullable ItemStack[] window) {
        List<ItemStack> stillInThePage = new ArrayList<>();
        for (ItemStack stack : openedWith) {
            if (!stack.getType().isAir()) {
                stillInThePage.add(stack.clone());
            }
        }

        List<ItemStack> putIn = new ArrayList<>();
        for (ItemStack stack : window) {
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            ItemStack owed = stack.clone();
            // Take this stack's amount out of what the page still holds, stack by stack, so a player
            // who added ten to a stack of five is owed ten and not fifteen.
            for (Iterator<ItemStack> stored = stillInThePage.iterator(); stored.hasNext() && owed.getAmount() > 0; ) {
                ItemStack candidate = stored.next();
                if (!candidate.isSimilar(owed)) {
                    continue;
                }
                int settled = Math.min(candidate.getAmount(), owed.getAmount());
                owed.setAmount(owed.getAmount() - settled);
                candidate.setAmount(candidate.getAmount() - settled);
                if (candidate.getAmount() <= 0) {
                    stored.remove();
                }
            }
            if (owed.getAmount() > 0) {
                putIn.add(owed);
            }
        }
        // What is left of the page is what the player took out and the page still holds.
        return new Moved(List.copyOf(putIn), List.copyOf(stillInThePage));
    }

    /**
     * The player's inventory as the stored page leaves it, or nothing when it would not fit.
     *
     * <p>What is on the cursor is the player's: an item picked out of their inventory is theirs, and
     * one picked out of the window was taken out and comes off again below. What the player took out
     * and no longer holds, dropped or handed on, cannot come off, and the page still holds it, so
     * nothing is written twice. What they put in goes back where it fits; when it does not fit, the
     * answer is nothing, and the checkpoint waits for the window to close.
     */
    static @Nullable ItemStack @Nullable [] asStored(
            @Nullable ItemStack[] inventory, @Nullable ItemStack cursor, Moved moved) {
        @Nullable ItemStack[] copy = new ItemStack[inventory.length];
        for (int slot = 0; slot < inventory.length; slot++) {
            ItemStack stack = inventory[slot];
            copy[slot] = stack == null || stack.getType().isAir() ? null : stack.clone();
        }
        int storage = Math.min(STORAGE_SLOTS, copy.length);
        if (cursor != null && !cursor.getType().isAir() && !add(copy, storage, cursor.clone())) {
            return null;
        }
        for (ItemStack takenOut : moved.takenOut()) {
            remove(copy, storage, takenOut.clone());
        }
        for (ItemStack putIn : moved.putIn()) {
            if (!add(copy, storage, putIn.clone())) {
                return null;
            }
        }
        return copy;
    }

    private static void remove(@Nullable ItemStack[] slots, int storage, ItemStack stack) {
        for (int slot = 0; slot < storage && stack.getAmount() > 0; slot++) {
            ItemStack held = slots[slot];
            if (held == null || !held.isSimilar(stack)) {
                continue;
            }
            int taken = Math.min(held.getAmount(), stack.getAmount());
            stack.setAmount(stack.getAmount() - taken);
            if (held.getAmount() == taken) {
                slots[slot] = null;
            } else {
                held.setAmount(held.getAmount() - taken);
            }
        }
    }

    private static boolean add(@Nullable ItemStack[] slots, int storage, ItemStack stack) {
        int max = Math.max(1, stack.getMaxStackSize());
        for (int slot = 0; slot < storage && stack.getAmount() > 0; slot++) {
            ItemStack held = slots[slot];
            if (held != null && held.isSimilar(stack) && held.getAmount() < max) {
                int moved = Math.min(max - held.getAmount(), stack.getAmount());
                held.setAmount(held.getAmount() + moved);
                stack.setAmount(stack.getAmount() - moved);
            }
        }
        for (int slot = 0; slot < storage && stack.getAmount() > 0; slot++) {
            if (slots[slot] == null) {
                ItemStack placed = stack.clone();
                placed.setAmount(Math.min(max, stack.getAmount()));
                slots[slot] = placed;
                stack.setAmount(stack.getAmount() - placed.getAmount());
            }
        }
        return stack.getAmount() <= 0;
    }
}
