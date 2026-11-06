package com.pingme.reminder.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Inbound contract for {@code POST /reminders}.
 */
public record CreateReminderRequest(
		@NotNull Long userId,
		@NotBlank @Size(max = 255) String title,
		@Size(max = 4000) String description,
		@NotNull Instant scheduledTime) {
}
