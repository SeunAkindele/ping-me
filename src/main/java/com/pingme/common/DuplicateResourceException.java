package com.pingme.common;

/**
 * Signals a uniqueness constraint violation (for example, duplicate user email).
 * Mapped to HTTP 409 by {@link com.pingme.api.GlobalExceptionHandler}.
 */
public final class DuplicateResourceException extends RuntimeException {

	public DuplicateResourceException(String message) {
		super(message);
	}

}
