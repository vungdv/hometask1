package vn.danang.polaris.assistant.ai;

import jakarta.annotation.Nullable;

/**
 * A result that may have been produced by calling a model provider.
 */
public interface ModelCallResult {

    /**
     * The provider call behind this result, or {@code null} when the result was produced without calling the
     * provider (local fallback, missing configuration).
     */
    @Nullable
    ModelCall modelCall();
}
