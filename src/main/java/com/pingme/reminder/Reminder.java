package com.pingme.reminder;

import com.pingme.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/**
 * Stage 2/3 — Reminder aggregate mapped to {@code reminders} with a lazy {@link User} association.
 *
 * <p>Status transitions ({@link #markProcessing}, {@link #markSent}, {@link #markFailed}) encode
 * business rules used later by the scheduler and notification worker. {@link #isDue} supports due
 * detection without leaking SQL into application services.
 */
@Entity
@Table(name = "reminders")
public final class Reminder {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(optional = false, fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id", nullable = false, updatable = false)
	private User user;

	@Column(nullable = false, updatable = false)
	private String title;

	@Column(updatable = false)
	private String description;

	@Column(name = "scheduled_time", nullable = false, updatable = false)
	private Instant scheduledTime;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ReminderStatus status;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected Reminder() {
		// Required by JPA; use factory methods instead.
	}

	private Reminder(
			Long id,
			User user,
			String title,
			String description,
			Instant scheduledTime,
			ReminderStatus status,
			Instant createdAt) {
		this.id = id;
		this.user = Objects.requireNonNull(user, "user is required");
		this.title = requireTitle(title);
		this.description = normalizeDescription(description);
		this.scheduledTime = Objects.requireNonNull(scheduledTime, "scheduledTime is required");
		this.status = Objects.requireNonNull(status, "status is required");
		this.createdAt = Objects.requireNonNull(createdAt, "createdAt is required");
	}

	public static Reminder create(User user, String title, String description, Instant scheduledTime) {
		return new Reminder(null, user, title, description, scheduledTime, ReminderStatus.PENDING, Instant.now());
	}

	public static Reminder restore(
			Long id,
			User user,
			String title,
			String description,
			Instant scheduledTime,
			ReminderStatus status,
			Instant createdAt) {
		Objects.requireNonNull(id, "id is required");
		return new Reminder(id, user, title, description, scheduledTime, status, createdAt);
	}

	public void assignId(Long id) {
		if (this.id != null) {
			throw new IllegalStateException("id is already assigned");
		}
		this.id = Objects.requireNonNull(id, "id is required");
	}

	public void markProcessing() {
		transitionTo(ReminderStatus.PROCESSING);
	}

	public void markSent() {
		transitionTo(ReminderStatus.SENT);
	}

	public void markFailed() {
		transitionTo(ReminderStatus.FAILED);
	}

	public boolean isDue(Instant now) {
		Objects.requireNonNull(now, "now is required");
		return status == ReminderStatus.PENDING && !scheduledTime.isAfter(now);
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return user.getId();
	}

	public User getUser() {
		return user;
	}

	public String getTitle() {
		return title;
	}

	public String getDescription() {
		return description;
	}

	public Instant getScheduledTime() {
		return scheduledTime;
	}

	public ReminderStatus getStatus() {
		return status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	private void transitionTo(ReminderStatus next) {
		if (!status.canTransitionTo(next)) {
			throw new IllegalStateException("Cannot move reminder from %s to %s".formatted(status, next));
		}
		this.status = next;
	}

	private static String requireTitle(String title) {
		if (title == null || title.isBlank()) {
			throw new IllegalArgumentException("title is required");
		}
		return title.trim();
	}

	private static String normalizeDescription(String description) {
		if (description == null || description.isBlank()) {
			return null;
		}
		return description.trim();
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof Reminder reminder)) {
			return false;
		}
		return id != null && id.equals(reminder.id);
	}

	// Stable while id is assigned after construction. Equality is based on id.
	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

	@Override
	public String toString() {
		return "Reminder{id=%s, userId=%s, status=%s, scheduledTime=%s}".formatted(id, getUserId(), status, scheduledTime);
	}

}
