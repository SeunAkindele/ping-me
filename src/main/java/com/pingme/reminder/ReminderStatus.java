package com.pingme.reminder;

/**
 * Stage 2 — Reminder lifecycle states stored as strings in PostgreSQL.
 *
 * <p>Valid transitions are enforced in the domain ({@link #canTransitionTo(ReminderStatus)}) so
 * illegal state changes fail before they reach the database.
 */
public enum ReminderStatus {

	PENDING,
	PROCESSING,
	SENT,
	FAILED;

	public boolean canTransitionTo(ReminderStatus next) {
		return switch (this) {
			case PENDING -> next == PROCESSING;
			case PROCESSING -> next == SENT || next == FAILED;
			case SENT, FAILED -> false;
		};
	}

}
