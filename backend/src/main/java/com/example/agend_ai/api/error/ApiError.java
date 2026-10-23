package com.example.agend_ai.api.error;

/** Тело ошибки: {"error": {...}, "request_id": "..."} (snake_case задаётся глобально). */
public record ApiError(Body error, String requestId) {

    public record Body(String code, String message, boolean retryable, Integer retryAfterSec, Fallback fallback) {
    }

    public static ApiError of(ErrorCode code, Integer retryAfterSec, String requestId) {
        return new ApiError(
                new Body(code.name(), code.message(), code.retryable(), retryAfterSec, code.fallback()),
                requestId);
    }
}
