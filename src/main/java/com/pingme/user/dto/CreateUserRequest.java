package com.pingme.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Inbound contract for {@code POST /users}.
 *
 * <p>Stage 4 — DTO: deliberately separate from {@link com.pingme.user.User} so HTTP shape
 * and persistence shape can evolve independently (validation rules, optional fields, versioning).
 */
public record CreateUserRequest(
		@NotBlank @Size(max = 255) String name,
		@NotBlank @Email @Size(max = 255) String email) {
}
