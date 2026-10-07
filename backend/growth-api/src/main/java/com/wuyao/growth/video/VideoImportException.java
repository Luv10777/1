package com.wuyao.growth.video;

/** Import decisions are explicit; neither handlers nor storage wrappers need to parse messages. */
public final class VideoImportException extends IllegalStateException {
    public enum Reason {
        OUTPUT_PROTECTED(false), MISSING_URL(false), INVALID_URL(false), REDIRECT_LIMIT(false),
        REDIRECT_LOCATION_MISSING(false), HTTP_REJECTED(false), RESULT_URL_UNAVAILABLE(false),
        EMPTY_RESPONSE(false), TOO_LARGE(false), INVALID_CONTENT(false),
        HTTP_TRANSIENT(true), TRANSFER_FAILED(true);

        private final boolean retryable;
        Reason(boolean retryable) { this.retryable = retryable; }
    }

    private final Reason reason;

    public VideoImportException(Reason reason, String detail) { this(reason, detail, null); }

    public VideoImportException(Reason reason, String detail, Throwable cause) {
        super("保存供应商视频失败：" + detail, cause);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
    public boolean retryable() { return reason.retryable; }
    public String errorCode() { return "VIDEO_IMPORT_" + reason.name(); }
}
