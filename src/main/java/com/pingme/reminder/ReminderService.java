package com.pingme.reminder;

import com.pingme.common.ResourceNotFoundException;
import com.pingme.reminder.dto.CreateReminderRequest;
import com.pingme.reminder.dto.ReminderResponse;
import com.pingme.user.User;
import com.pingme.user.UserService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for reminder CRUD.
 *
 * <p>Stage 4 — Service layer: coordinates {@link UserService} and {@link ReminderRepository}.
 * HTTP handlers stop here. Due-time claiming belongs to {@link ReminderClaimService}, which the
 * scheduler calls. This class does not send notifications.
 */
@Service
public class ReminderService {

	private final ReminderRepository reminderRepository;
	private final UserService userService;

	public ReminderService(ReminderRepository reminderRepository, UserService userService) {
		this.reminderRepository = reminderRepository;
		this.userService = userService;
	}

	@Transactional
	public ReminderResponse createReminder(CreateReminderRequest request) {
		User user = userService.requireUser(request.userId());
		Reminder reminder = Reminder.create(user, request.title(), request.description(), request.scheduledTime());
		return ReminderResponse.from(reminderRepository.save(reminder));
	}

	@Transactional(readOnly = true)
	public List<ReminderResponse> listReminders(Long userId) {
		List<Reminder> reminders = userId == null
				? reminderRepository.findAll()
				: reminderRepository.findByUser_Id(userId);
		return reminders.stream().map(ReminderResponse::from).toList();
	}

	@Transactional(readOnly = true)
	public ReminderResponse getReminder(Long id) {
		return ReminderResponse.from(requireReminder(id));
	}

	@Transactional
	public void deleteReminder(Long id) {
		Reminder reminder = requireReminder(id);
		reminderRepository.delete(reminder);
	}

	private Reminder requireReminder(Long id) {
		return reminderRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Reminder not found: " + id));
	}

}
