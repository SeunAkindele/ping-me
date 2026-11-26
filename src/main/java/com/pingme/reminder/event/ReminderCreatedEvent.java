package com.pingme.reminder.event;

import java.time.Instant;
import java.util.Objects;

/**
 * Stage 8 — Domain event: "a reminder was created and committed to the database".
 *
 * <h3>What an event is</h3>
 *
 * <p>An event is an immutable statement of a fact that <em>already happened</em>. That is why the
 * name is in the past tense ({@code ReminderCreated}, not {@code CreateReminder}). A command asks
 * someone to do something and can be refused; an event only reports, and nobody can refuse a fact.
 *
 * <p>The publisher ({@link com.pingme.reminder.ReminderService}) does not know who is listening,
 * how many listeners there are, or what they do. Adding a new reaction to "reminder created"
 * (send a confirmation, write an audit row, update a dashboard counter) means adding a listener,
 * never editing the service. That is the Open/Closed Principle applied across modules.
 *
 * <h3>Why the event carries data, not the {@code Reminder} entity</h3>
 *
 * <ul>
 *   <li><b>Thread safety.</b> Async listeners run on another thread, after the publishing
 *       transaction has closed. A JPA entity there is detached: touching a lazy association such
 *       as {@code reminder.getUser()} throws {@code LazyInitializationException}.</li>
 *   <li><b>Immutability.</b> An entity can be changed after the event fires, so two listeners
 *       could see two different "facts". A record is a frozen snapshot.</li>
 *   <li><b>Self-contained.</b> Listeners get everything they need without querying the
 *       database again ("event-carried state transfer"). This also makes the event ready to be
 *       serialised to JSON and published to RabbitMQ or Kafka later, unchanged.</li>
 * </ul>
 *
 * <p>The event is owned by the module that publishes it (the {@code reminder} module), because it
 * describes something that happened inside that module. Consumers depend on the publisher's
 * events, not the other way round.
 *
 * @param reminderId     database id of the new reminder
 * @param userId         owner of the reminder
 * @param recipientEmail where notifications for this reminder go; captured now so listeners need
 *                       not load the user
 * @param recipientName  display name of the owner
 * @param title          reminder title
 * @param scheduledTime  when the reminder is due
 * @param occurredAt     when the reminder was created (the moment the fact became true)
 */
public record ReminderCreatedEvent(
		Long reminderId,
		Long userId,
		String recipientEmail,
		String recipientName,
		String title,
		Instant scheduledTime,
		Instant occurredAt) {

	/**
	 * Compact constructor: an event with missing identity or timing is a bug in the publisher, so
	 * fail at the moment of publishing rather than later inside some listener on another thread,
	 * where the stack trace would no longer point at the culprit.
	 */
	public ReminderCreatedEvent {
		Objects.requireNonNull(reminderId, "reminderId is required");
		Objects.requireNonNull(userId, "userId is required");
		Objects.requireNonNull(scheduledTime, "scheduledTime is required");
		Objects.requireNonNull(occurredAt, "occurredAt is required");
	}

}
