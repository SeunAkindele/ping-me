package com.pingme.reminder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.pingme.user.User;
import com.pingme.user.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Stage 5 — Claim rules against real PostgreSQL. An in-memory fake would not take
 * {@code FOR UPDATE SKIP LOCKED}, so it cannot prove the duplicate-processing guard.
 */
@SpringBootTest(properties = "pingme.scheduler.enabled=false")
@Testcontainers
class ReminderClaimServiceTest {

	private static final Instant CLAIM_TIME = Instant.parse("2030-01-01T00:00:00Z");
	private static final Instant DUE = Instant.parse("2029-12-31T23:00:00Z");
	private static final Instant FUTURE = Instant.parse("2031-01-01T00:00:00Z");

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private ReminderClaimService reminderClaimService;

	@Autowired
	private ReminderRepository reminderRepository;

	@Autowired
	private UserRepository userRepository;

	@BeforeEach
	void clean() {
		reminderRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void claimsDuePendingRemindersIncludingThoseDueExactlyNow() {
		User user = saveUser();
		Reminder due = saveReminder(user, DUE);
		Reminder dueNow = saveReminder(user, CLAIM_TIME);
		Reminder future = saveReminder(user, FUTURE);

		int claimed = reminderClaimService.claimDueReminders(CLAIM_TIME, 100);

		assertEquals(2, claimed);
		assertEquals(ReminderStatus.PROCESSING, reload(due.getId()).getStatus());
		assertEquals(ReminderStatus.PROCESSING, reload(dueNow.getId()).getStatus());
		assertEquals(ReminderStatus.PENDING, reload(future.getId()).getStatus());
	}

	@Test
	void secondClaimDoesNotTakeRowsAlreadyProcessing() {
		User user = saveUser();
		Reminder due = saveReminder(user, DUE);

		assertEquals(1, reminderClaimService.claimDueReminders(CLAIM_TIME, 100));
		assertEquals(0, reminderClaimService.claimDueReminders(CLAIM_TIME, 100));
		assertEquals(ReminderStatus.PROCESSING, reload(due.getId()).getStatus());
	}

	@Test
	void leavesSentAndFailedRemindersAlone() {
		User user = saveUser();
		Reminder sent = saveReminder(user, DUE);
		sent.markProcessing();
		sent.markSent();
		reminderRepository.save(sent);

		Reminder failed = saveReminder(user, DUE);
		failed.markProcessing();
		failed.markFailed();
		reminderRepository.save(failed);

		assertEquals(0, reminderClaimService.claimDueReminders(CLAIM_TIME, 100));
		assertEquals(ReminderStatus.SENT, reload(sent.getId()).getStatus());
		assertEquals(ReminderStatus.FAILED, reload(failed.getId()).getStatus());
	}

	@Test
	void respectsBatchSizeAndLeavesTheRestPending() {
		User user = saveUser();
		saveReminder(user, DUE);
		saveReminder(user, DUE);
		saveReminder(user, DUE);

		assertEquals(2, reminderClaimService.claimDueReminders(CLAIM_TIME, 2));
		assertEquals(2, countWithStatus(ReminderStatus.PROCESSING));
		assertEquals(1, countWithStatus(ReminderStatus.PENDING));

		assertEquals(1, reminderClaimService.claimDueReminders(CLAIM_TIME, 2));
		assertEquals(3, countWithStatus(ReminderStatus.PROCESSING));
	}

	@Test
	void concurrentClaimsTakeEachDueReminderOnce() throws Exception {
		User user = saveUser();
		int dueCount = 20;
		for (int i = 0; i < dueCount; i++) {
			saveReminder(user, DUE);
		}

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			CountDownLatch start = new CountDownLatch(1);
			Callable<Integer> claim = () -> {
				start.await();
				return reminderClaimService.claimDueReminders(CLAIM_TIME, 100);
			};
			Future<Integer> first = pool.submit(claim);
			Future<Integer> second = pool.submit(claim);
			start.countDown();

			assertEquals(dueCount, first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS));
		} finally {
			pool.shutdownNow();
		}

		assertEquals(dueCount, countWithStatus(ReminderStatus.PROCESSING));
		assertEquals(0, countWithStatus(ReminderStatus.PENDING));
	}

	@Test
	void rejectsNonPositiveBatchSize() {
		assertThrows(IllegalArgumentException.class, () -> reminderClaimService.claimDueReminders(CLAIM_TIME, 0));
	}

	private User saveUser() {
		return userRepository.save(User.create("Ada Lovelace", "ada@example.com"));
	}

	private Reminder saveReminder(User user, Instant scheduledTime) {
		return reminderRepository.save(Reminder.create(user, "Call John", "Discuss project", scheduledTime));
	}

	private Reminder reload(Long id) {
		return reminderRepository.findById(id).orElseThrow();
	}

	private long countWithStatus(ReminderStatus status) {
		List<Reminder> reminders = new ArrayList<>(reminderRepository.findAll());
		return reminders.stream().filter(reminder -> reminder.getStatus() == status).count();
	}

}
