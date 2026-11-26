package com.pingme.reminder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pingme.reminder.dto.CreateReminderRequest;
import com.pingme.reminder.dto.ReminderResponse;
import com.pingme.reminder.event.ReminderCreatedEvent;
import com.pingme.reminder.event.ReminderSentEvent;
import com.pingme.user.User;
import com.pingme.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Stage 8 — Verifies who publishes which event, and when listeners are allowed to see it.
 *
 * <p>Two tools are used, and the difference between them is the lesson:
 *
 * <ul>
 *   <li>{@link ApplicationEvents} ({@code @RecordApplicationEvents}) records every event at the
 *       moment {@code publishEvent} is called, whether or not the transaction later commits. It
 *       answers "did the service <em>publish</em>?"</li>
 *   <li>{@link AfterCommitProbe} is a real {@code AFTER_COMMIT} listener, synchronous so the test
 *       needs no waiting. It answers "did a listener <em>receive</em> it?" and proves that a
 *       rolled-back transaction delivers nothing.</li>
 * </ul>
 *
 * <p>The console sender's simulated failures are switched on so a {@code [fail-permanent]} title
 * produces a real failed delivery without a test double.
 */
@SpringBootTest(properties = {
		"pingme.scheduler.enabled=false",
		"pingme.worker.enabled=false",
		"pingme.notification.console.simulate-failures=true"
})
@Testcontainers
@RecordApplicationEvents
class ReminderEventsTest {

	private static final Instant T0 = Instant.parse("2030-01-01T00:00:00Z");
	private static final Instant DUE = Instant.parse("2029-12-31T23:00:00Z");

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private ApplicationEvents events;

	@Autowired
	private AfterCommitProbe probe;

	@Autowired
	private ReminderService reminderService;

	@Autowired
	private ReminderClaimService reminderClaimService;

	@Autowired
	private ReminderJobService reminderJobService;

	@Autowired
	private ReminderJobRepository reminderJobRepository;

	@Autowired
	private ReminderRepository reminderRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private User user;

	@BeforeEach
	void setUp() {
		reminderJobRepository.deleteAll();
		reminderRepository.deleteAll();
		userRepository.deleteAll();
		probe.clear();
		user = userRepository.save(User.create("Ada Lovelace", "ada@example.com"));
		// Events from the set-up above must not leak into assertions.
		events.clear();
	}

	@Test
	void creatingReminderPublishesReminderCreatedEventWithSnapshotData() {
		ReminderResponse created = reminderService.createReminder(
				new CreateReminderRequest(user.getId(), "Call John", "Discuss project", DUE));

		List<ReminderCreatedEvent> published = events.stream(ReminderCreatedEvent.class).toList();
		assertEquals(1, published.size());
		ReminderCreatedEvent event = published.get(0);
		assertEquals(created.id(), event.reminderId());
		assertEquals(user.getId(), event.userId());
		assertEquals("ada@example.com", event.recipientEmail());
		assertEquals("Call John", event.title());
		assertEquals(DUE, event.scheduledTime());

		assertEquals(List.of(event), probe.received(), "listener receives the event after commit");
	}

	@Test
	void rolledBackCreationIsPublishedButNeverDeliveredToAfterCommitListeners() {
		// Run createReminder inside an outer transaction that we then roll back. The reminder's own
		// @Transactional joins the outer one, so its INSERT is undone too.
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			reminderService.createReminder(new CreateReminderRequest(user.getId(), "Never happened", null, DUE));
			status.setRollbackOnly();
		});

		assertEquals(1, events.stream(ReminderCreatedEvent.class).count(), "the service did publish");
		assertTrue(probe.received().isEmpty(), "AFTER_COMMIT listeners must not see a rolled-back fact");
		assertEquals(0, reminderRepository.count());
	}

	@Test
	void failedValidationPublishesNothing() {
		assertThrows(RuntimeException.class, () -> reminderService.createReminder(
				new CreateReminderRequest(999_999L, "Unknown user", null, DUE)));

		assertEquals(0, events.stream(ReminderCreatedEvent.class).count());
	}

	@Test
	void successfulDeliveryPublishesReminderSentEvent() {
		Long reminderId = createDueReminder("Call John");
		Long jobId = claimSingleJob();

		reminderJobService.processJob(jobId, T0);

		List<ReminderSentEvent> published = events.stream(ReminderSentEvent.class).toList();
		assertEquals(1, published.size());
		ReminderSentEvent event = published.get(0);
		assertEquals(reminderId, event.reminderId());
		assertEquals(jobId, event.jobId());
		assertEquals(1, event.attemptCount());
		assertEquals(T0, event.occurredAt());
		assertTrue(probe.received().contains(event));
	}

	@Test
	void failedDeliveryPublishesNoReminderSentEvent() {
		createDueReminder("Call John [fail-permanent]");

		reminderJobService.processJob(claimSingleJob(), T0);

		assertEquals(0, events.stream(ReminderSentEvent.class).count());
	}

	// ── helpers ────────────────────────────────────────────────────────────────

	private Long createDueReminder(String title) {
		Reminder reminder = reminderRepository.save(Reminder.create(user, title, null, DUE));
		assertEquals(1, reminderClaimService.claimDueReminders(T0, 10));
		return reminder.getId();
	}

	private Long claimSingleJob() {
		List<Long> ids = reminderJobService.claimQueuedJobs(T0, 10);
		assertEquals(1, ids.size());
		return ids.get(0);
	}

	/** Synchronous AFTER_COMMIT listener that remembers every event it was given. */
	static final class AfterCommitProbe {

		private final List<Object> received = new CopyOnWriteArrayList<>();

		@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
		public void onCreated(ReminderCreatedEvent event) {
			received.add(event);
		}

		@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
		public void onSent(ReminderSentEvent event) {
			received.add(event);
		}

		List<Object> received() {
			return List.copyOf(received);
		}

		void clear() {
			received.clear();
		}

	}

	@TestConfiguration
	static class Config {

		@Bean
		AfterCommitProbe afterCommitProbe() {
			return new AfterCommitProbe();
		}

	}

}
