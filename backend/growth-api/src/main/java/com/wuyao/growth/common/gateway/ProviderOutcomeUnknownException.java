package com.wuyao.growth.common.gateway;

/**
 * The provider may have accepted a request even though its response was lost.
 * Retrying automatically is unsafe for paid, synchronous image APIs.
 */
public class ProviderOutcomeUnknownException extends RuntimeException {
    public ProviderOutcomeUnknownException(String message, Throwable cause) {
        super(message, cause);
    }
}
