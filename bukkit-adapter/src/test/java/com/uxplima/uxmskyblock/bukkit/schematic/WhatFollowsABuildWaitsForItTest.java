package com.uxplima.uxmskyblock.bukkit.schematic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What follows an island's build, the player's arrival, runs once the build stands: at once for a build done
 * at once, so nothing about its order changes, and when a pasted build finishes otherwise. A failed build is
 * named and the player still arrives.
 */
class WhatFollowsABuildWaitsForItTest {

    private final List<String> happened = new ArrayList<>();

    @Test
    @DisplayName("A build done at once is followed at once, and what follows may throw to its caller")
    void doneAtOnce() {
        StarterSchematicEngine.afterBuilt(
                CompletableFuture.completedFuture(null), failure -> happened.add("failed"), () -> happened.add("then"));

        assertThat(happened).containsExactly("then");
        assertThatThrownBy(() -> StarterSchematicEngine.afterBuilt(
                        CompletableFuture.completedFuture(null), failure -> {}, () -> {
                            throw new IllegalStateException("arrival");
                        }))
                .hasMessage("arrival");
    }

    @Test
    @DisplayName("A build finishing later is followed when it finishes, and a failure is named first")
    void finishingLater() {
        CompletableFuture<Void> pasting = new CompletableFuture<>();
        StarterSchematicEngine.afterBuilt(pasting, failure -> happened.add("failed"), () -> happened.add("then"));

        assertThat(happened).isEmpty();
        pasting.completeExceptionally(new IllegalStateException("the file is gone"));
        assertThat(happened).containsExactly("failed", "then");

        CompletableFuture<Void> failedAtOnce = CompletableFuture.failedFuture(new IllegalStateException("no"));
        StarterSchematicEngine.afterBuilt(failedAtOnce, failure -> happened.add("failed"), () -> happened.add("then"));
        assertThat(happened).containsExactly("failed", "then", "failed", "then");
    }

    @Test
    @DisplayName("What follows a later finish and throws is written down, not thrown into the paste")
    void aLaterThrowIsKept() {
        CompletableFuture<Void> pasting = new CompletableFuture<>();
        StarterSchematicEngine.afterBuilt(pasting, failure -> {}, () -> {
            throw new IllegalStateException("arrival");
        });

        pasting.complete(null);

        assertThat(pasting).isCompleted();
    }
}
