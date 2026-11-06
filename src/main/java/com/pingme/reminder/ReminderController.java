package com.pingme.reminder;

import com.pingme.reminder.dto.CreateReminderRequest;
import com.pingme.reminder.dto.ReminderResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP adapter for reminder resources.
 *
 * <p>Endpoints (Stage 4):
 * <ul>
 *   <li>{@code POST /reminders} — create a reminder for an existing user</li>
 *   <li>{@code GET /reminders} — list reminders; optional {@code userId} filter</li>
 *   <li>{@code GET /reminders/{id}} — fetch one reminder</li>
 *   <li>{@code DELETE /reminders/{id}} — remove a reminder</li>
 * </ul>
 */
@RestController
@RequestMapping("/reminders")
public class ReminderController {

	private final ReminderService reminderService;

	public ReminderController(ReminderService reminderService) {
		this.reminderService = reminderService;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ReminderResponse createReminder(@Valid @RequestBody CreateReminderRequest request) {
		return reminderService.createReminder(request);
	}

	@GetMapping
	public List<ReminderResponse> listReminders(@RequestParam(required = false) Long userId) {
		return reminderService.listReminders(userId);
	}

	@GetMapping("/{id}")
	public ReminderResponse getReminder(@PathVariable Long id) {
		return reminderService.getReminder(id);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteReminder(@PathVariable Long id) {
		reminderService.deleteReminder(id);
	}

}
