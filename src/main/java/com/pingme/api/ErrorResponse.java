package com.pingme.api;

import java.time.Instant;

/**
 * Stable JSON shape for API errors returned by {@link GlobalExceptionHandler}.
 */
public record ErrorResponse(Instant timestamp, int status, String error, String message) {
}
