package com.pingme.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Stage 2/3 — User aggregate root mapped to the {@code users} table.
 *
 * <p>Domain rules (name/email validation) live here, not in controllers. Factory methods
 * ({@link #create}, {@link #restore}) distinguish new entities from rehydrated rows. JPA requires a
 * protected no-args constructor for reflection-based instantiation.
 */
@Entity
@Table(name = "users")
public final class User {

	private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, updatable = false)
	private String name;

	@Column(nullable = false, updatable = false, unique = true)
	private String email;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected User() {
		// Required by JPA; use factory methods instead.
	}

	private User(Long id, String name, String email, Instant createdAt) {
		this.id = id;
		this.name = requireName(name);
		this.email = requireEmail(email);
		this.createdAt = Objects.requireNonNull(createdAt, "createdAt is required");
	}

	public static User create(String name, String email) {
		return new User(null, name, email, Instant.now());
	}

	public static User restore(Long id, String name, String email, Instant createdAt) {
		Objects.requireNonNull(id, "id is required");
		return new User(id, name, email, createdAt);
	}

	public void assignId(Long id) {
		if (this.id != null) {
			throw new IllegalStateException("id is already assigned");
		}
		this.id = Objects.requireNonNull(id, "id is required");
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getEmail() {
		return email;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	private static String requireName(String name) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("name is required");
		}
		return name.trim();
	}

	private static String requireEmail(String email) {
		if (email == null || email.isBlank()) {
			throw new IllegalArgumentException("email is required");
		}
		String normalized = email.trim().toLowerCase(Locale.ROOT);
		if (!EMAIL.matcher(normalized).matches()) {
			throw new IllegalArgumentException("email is invalid");
		}
		return normalized;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof User user)) {
			return false;
		}
		return id != null && id.equals(user.id);
	}

	// Stable while id is assigned after construction. Equality is based on id.
	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

	@Override
	public String toString() {
		return "User{id=%s, email=%s}".formatted(id, email);
	}

}
