package com.uxplima.uxmskyblock.bukkit.bootstrap;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.menu.SkyblockMenuEngine;

/** The verbs a skyblock menu file may name, and what each one does. */
final class SkyblockMenuVerbs {

    private SkyblockMenuVerbs() {}

    /**
     * The verbs a skyblock menu file may name, on top of the five every plugin has.
     *
     * <p>Each one is what the window used to do in Java when its slot was clicked. The file decides
     * which slot runs which verb; this decides what each verb means.
     */
    static void register(SkyblockMenuEngine engine, Messages messages, java.util.function.UnaryOperator<String> typed) {
        engine.action("skyblock:teleport-home", ctx -> {
            ctx.player().closeInventory();
            ctx.player().performCommand(typed.apply("home"));
        });
        engine.action("skyblock:bank", ctx -> {
            ctx.player().closeInventory();
            messages.send(ctx.player(), "menu.control.bank_hint");
        });
        engine.action("skyblock:upgrades", ctx -> {
            ctx.player().closeInventory();
            ctx.player().performCommand(typed.apply("upgrades"));
        });
        engine.action("skyblock:members", ctx -> {
            ctx.player().closeInventory();
            ctx.player().performCommand(typed.apply("members"));
        });
        engine.action("skyblock:settings", ctx -> {
            ctx.player().closeInventory();
            ctx.player().performCommand(typed.apply("settings"));
        });
        // The invite button sent a hint message, because there was no command behind it. The name
        // the player types is the verb's argument, so one slot serves every invite.
        engine.action("skyblock:invite", ctx -> {
            String name = ctx.arg().strip();
            ctx.player().closeInventory();
            if (name.isEmpty()) {
                messages.send(ctx.player(), "menu.control.members_hint");
                return;
            }
            ctx.player().performCommand(typed.apply("invite " + name));
        });
        engine.action("skyblock:shop", ctx -> {
            ctx.player().closeInventory();
            ctx.player().performCommand(typed.apply("shop"));
        });
        // The upgrade key is the verb's argument, so one verb serves every upgrade a file names and
        // an operator can add a slot for a new one without a line of Java.
        // skyblock:island:<branch> <arguments> runs an island command under the operator's names for it,
        // so a menu file keeps working when commands.conf renames the root or the branch.
        engine.action("skyblock:island", ctx -> {
            String line = ctx.arg().strip();
            if (!line.isEmpty()) {
                ctx.player().performCommand(typed.apply(line));
            }
        });
        // The window stays up: once the purchase is written it is drawn again with the new tier, and a
        // refusal is said in chat while the player still sees what they could afford.
        engine.action("skyblock:buy-upgrade", ctx -> {
            String upgradeKey = ctx.arg().strip();
            if (upgradeKey.isEmpty()) {
                messages.send(ctx.player(), "menu.control.upgrade_unnamed");
                return;
            }
            ctx.player().performCommand(typed.apply("upgrades buy " + upgradeKey));
        });
    }
}
