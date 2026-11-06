package com.pingme.common;

/**
 * Signals that a client referenced an entity that does not exist.
 * Mapped to HTTP 404 by {@link com.pingme.api.GlobalExceptionHandler}.
 */
public final class ResourceNotFoundException extends RuntimeException {

	public ResourceNotFoundException(String message) {
		super(message);
	}

}
