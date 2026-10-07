package com.wuyao.growth.common.task;

/** Signals that the task has a permanent external error and must not be replayed. */
public class NonRetryableTaskException extends RuntimeException {
    private final String errorCode;

    public NonRetryableTaskException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() { return errorCode; }
}
