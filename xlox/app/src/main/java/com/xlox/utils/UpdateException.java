package com.xlox.utils;

/**
 * A failure with a message that is safe to show to the user as-is.
 * {@link #retryable} tells callers whether trying again can help
 * (network hiccup) or not (no release published, invalid file).
 */
public class UpdateException extends Exception {
    public final boolean retryable;

    public UpdateException(String userMessage, boolean retryable) {
        super(userMessage);
        this.retryable = retryable;
    }

    public UpdateException(String userMessage, boolean retryable, Throwable cause) {
        super(userMessage, cause);
        this.retryable = retryable;
    }
}
