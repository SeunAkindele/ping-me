package com.pingme.reminder.dto;

import com.pingme.reminder.Reminder;
import com.pingme.reminder.ReminderStatus;
import java.time.Instant;

/**
 * Outbound contract for reminder resources.
 */
public record ReminderResponse(
		Long id,
		Long userId,
		String title,
		String description,
		Instant scheduledTime,
		ReminderStatus status,
		Instant createdAt) {

	public static ReminderResponse from(Reminder reminder) {
		return new ReminderResponse(
				reminder.getId(),
				reminder.getUserId(),
				reminder.getTitle(),
				reminder.getDescription(),
				reminder.getScheduledTime(),
				reminder.getStatus(),
				reminder.getCreatedAt());
	}

}
