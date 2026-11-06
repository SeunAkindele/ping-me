package com.pingme.user.dto;

import com.pingme.user.User;
import java.time.Instant;

/**
 * Outbound contract for user resources. Never exposes internal entity graphs to the wire.
 */
public record UserResponse(Long id, String name, String email, Instant createdAt) {

	public static UserResponse from(User user) {
		return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getCreatedAt());
	}

}
