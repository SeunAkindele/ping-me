package com.pingme.reminder;

import com.pingme.common.ResourceNotFoundException;
import com.pingme.reminder.dto.CreateReminderRequest;
import com.pingme.reminder.dto.ReminderResponse;
import com.pingme.reminder.event.ReminderCreatedEvent;
import com.pingme.user.User;
import com.pingme.user.UserService;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for reminder CRUD.
 *
 * <p>Stage 4 — Service layer: coordinates {@link UserService} and {@link ReminderRepository}.
 * HTTP handlers stop here. Due-time claiming belongs to {@link ReminderClaimService}, which the
 * scheduler calls. This class does not send notifications.
 *
 * <p>Stage 8 — After creating a reminder, this service announces the fact by publishing a
 * {@link ReminderCreatedEvent}. It does not know, or care, who reacts. Before events, adding a
 * "send a confirmation" feature would have meant injecting a notification dependency here and
 * calling it directly, coupling reminder creation to every future side effect.
 */
@Service
public class ReminderService {

	private final ReminderRepository reminderRepository;
	private final UserService userService;
	private final ApplicationEventPublisher eventPublisher;

	/**
	 * Stage 8 change: {@link ApplicationEventPublisher} is Spring's built-in event bus. It is an
	 * interface, so this service depends on an abstraction, and tests can record or replace it.
	 */
	public ReminderService(
			ReminderRepository reminderRepository,
			UserService userService,
			ApplicationEventPublisher eventPublisher) {
		this.reminderRepository = reminderRepository;
		this.userService = userService;
		this.eventPublisher = eventPublisher;
	}

	/**
	 * Creates a reminder and publishes {@link ReminderCreatedEvent}.
	 *
	 * <p><b>Publishing inside the transaction is safe here.</b> The event is handed to the bus now,
	 * but every listener in this application is a {@code @TransactionalEventListener} bound to
	 * {@code AFTER_COMMIT}. Spring parks the event until the transaction commits, and discards it
	 * if the transaction rolls back. So no listener can ever confirm a reminder that does not
	 * exist in the database.
	 *
	 * <p>The event is published after {@code save}, because the {@code IDENTITY} id is only
	 * assigned once the INSERT runs, and the event must carry that id.
	 */
	@Transactional
	public ReminderResponse createReminder(CreateReminderRequest request) {
		User user = userService.requireUser(request.userId());
		Reminder reminder = reminderRepository.save(
				Reminder.create(user, request.title(), request.description(), request.scheduledTime()));

		eventPublisher.publishEvent(new ReminderCreatedEvent(
				reminder.getId(),
				user.getId(),
				user.getEmail(),
				user.getName(),
				reminder.getTitle(),
				reminder.getScheduledTime(),
				reminder.getCreatedAt()));

		return ReminderResponse.from(reminder);
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
