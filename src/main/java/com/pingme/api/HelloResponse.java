package com.pingme.api;

/**
 * Stage 1 — Response DTO (Java {@code record}): immutable JSON body for {@code GET /hello}.
 */
public record HelloResponse(String message) {
}
