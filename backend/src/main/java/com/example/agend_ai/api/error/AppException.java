package com.example.agend_ai.api.error;

public class AppException extends RuntimeException {

    private final ErrorCode code;
    private final Integer retryAfterSec;

    public AppException(ErrorCode code) {
        this(code, null);
    }

    public AppException(ErrorCode code, Integer retryAfterSec) {
        super(code.name(), null, false, false);
        this.code = code;
        this.retryAfterSec = retryAfterSec;
    }

    public ErrorCode getCode() {
        return code;
    }

    public Integer getRetryAfterSec() {
        return retryAfterSec;
    }
}
