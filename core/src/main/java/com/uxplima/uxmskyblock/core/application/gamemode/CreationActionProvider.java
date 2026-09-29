package com.uxplima.uxmskyblock.core.application.gamemode;

/**
 * One thing a new island's start does: lay a platform, set a OneBlock island's block, paste a structure.
 *
 * <p>A preset lists the actions its start runs by name, and a game mode brings the providers that
 * answer them. No part of island creation asks which game mode it is making: it runs the list.
 *
 * @param <C> where the action happens, which the server's side describes
 */
public interface CreationActionProvider<C> {

    /** The name a preset's {@code start} list writes for this action, such as {@code uxm:platform}. */
    String actionId();

    /** Does the action at the place the context describes, on the thread that owns that place. */
    void apply(C context);
}
