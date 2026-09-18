package com.uxplima.uxmskyblock.core.domain.module;

/**
 * Thrown when a Tier 1 (Core Required) or Tier 2 (Required Capability Provider) module
 * fails during initialization, mandating failsafe plugin shutdown to protect state integrity.
 */
public final class FailsafePluginShutdownException extends RuntimeException {
    public FailsafePluginShutdownException(String message) {
        super(message);
    }

    public FailsafePluginShutdownException(String message, Throwable cause) {
        super(message, cause);
    }
}
