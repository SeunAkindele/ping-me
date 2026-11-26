package com.pingme.reminder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pingme.notification.NotificationSender;
import com.pingme.notification.PermanentNotificationException;
import com.pingme.notification.ReminderNotification;
import com.pingme.notification.TransientNotificationException;
import com.pingme.user.User;
import com.pingme.user.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Stage 7 — Retry, backoff, dead-lettering and stuck-job recovery against real PostgreSQL.
 *
 * <p>Both background loops are disabled so the test drives every step by hand with explicit
 * timestamps. That is why services take {@code Instant now} as a parameter: the test can jump
 * the clock forward 30 seconds instead of sleeping for 30 seconds.
 *
 * <p>The real {@code ConsoleNotificationSender} is replaced by {@link ScriptedSender}, a fake
 * that succeeds or throws according to a script each test writes. A fake is preferred over a
 * mocking library here because the behaviour being tested is the sequence of outcomes.
 */
@SpringBootTest(properties = {
		"pingme.scheduler.enabled=false",
		"pingme.worker.enabled=false",
		"pingme.retry.max-attempts=3",
		"pingme.retry.initial-backoff=30s",
		"pingme.retry.multiplier=2.0",
		"pingme.retry.max-backoff=10m"
})
@Testcontainers
class ReminderJobRetryTest {

	private static final Instant T0 = Instant.parse("2030-01-01T00:00:00Z");
	private static final Instant DUE = Instant.parse("2029-12-31T23:00:00Z");

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

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
	private ScriptedSender sender;

	@Autowired
	private ReminderService reminderService;

	@BeforeEach
	void clean() {
		// Children before parents: reminder_jobs → reminders → users (foreign keys).
		reminderJobRepository.deleteAll();
		reminderRepository.deleteAll();
		userRepository.deleteAll();
		sender.reset();
	}

	@Test
	void transientFailureRequeuesJobWithBackoffAndKeepsReminderProcessing() {
		Long reminderId = createDueReminderAndEnqueueJob();
		sender.script(transientFailure());

		Long jobId = claimSingleJob(T0);
		reminderJobService.processJob(jobId, T0);

		ReminderJob job = reloadJob(jobId);
		assertEquals(ReminderJobStatus.QUEUED, job.getStatus());
		assertEquals(1, job.getAttemptCount());
		assertEquals(T0.plusSeconds(30), job.getNextAttemptAt());
		assertTrue(job.getLastError().contains("TransientNotificationException"));
		assertNull(job.getStartedAt());
		assertNull(job.getProcessedAt(), "a job waiting for retry has not finished");
		assertEquals(ReminderStatus.PROCESSING, reloadReminder(reminderId).getStatus());
	}

	@Test
	void jobIsInvisibleToWorkersUntilBackoffElapses() {
		createDueReminderAndEnqueueJob();
		sender.script(transientFailure());
		reminderJobService.processJob(claimSingleJob(T0), T0);

		assertEquals(List.of(), reminderJobService.claimQueuedJobs(T0.plusSeconds(29), 10));
		assertEquals(1, reminderJobService.claimQueuedJobs(T0.plusSeconds(30), 10).size());
	}

	@Test
	void succeedsOnRetryAfterTransientFailure() {
		Long reminderId = createDueReminderAndEnqueueJob();
		sender.script(transientFailure(), success());

		Long jobId = claimSingleJob(T0);
		reminderJobService.processJob(jobId, T0);

		Instant retryTime = T0.plusSeconds(30);
		assertEquals(jobId, claimSingleJob(retryTime));
		reminderJobService.processJob(jobId, retryTime);

		ReminderJob job = reloadJob(jobId);
		assertEquals(ReminderJobStatus.COMPLETED, job.getStatus());
		assertEquals(2, job.getAttemptCount());
		assertEquals(retryTime, job.getProcessedAt());
		assertNotNull(job.getLastError(), "history of the earlier failure is kept");
		assertEquals(ReminderStatus.SENT, reloadReminder(reminderId).getStatus());
	}

	@Test
	void deadLettersAfterMaxAttemptsWithExponentialBackoffBetweenThem() {
		Long reminderId = createDueReminderAndEnqueueJob();
		sender.script(transientFailure(), transientFailure(), transientFailure());

		// Attempt 1 at T0 → retry after 30s.
		Long jobId = claimSingleJob(T0);
		reminderJobService.processJob(jobId, T0);
		assertEquals(T0.plusSeconds(30), reloadJob(jobId).getNextAttemptAt());

		// Attempt 2 at T0+30s → retry after 60s (doubled).
		Instant second = T0.plusSeconds(30);
		claimSingleJob(second);
		reminderJobService.processJob(jobId, second);
		assertEquals(second.plusSeconds(60), reloadJob(jobId).getNextAttemptAt());

		// Attempt 3 at T0+90s → no attempts left → dead letter.
		Instant third = second.plusSeconds(60);
		claimSingleJob(third);
		reminderJobService.processJob(jobId, third);

		ReminderJob job = reloadJob(jobId);
		assertEquals(ReminderJobStatus.FAILED, job.getStatus());
		assertEquals(3, job.getAttemptCount());
		assertEquals(third, job.getProcessedAt());
		assertTrue(job.getLastError().startsWith("Gave up after 3 attempt(s)"));
		assertEquals(ReminderStatus.FAILED, reloadReminder(reminderId).getStatus());
		assertEquals(3, sender.calls());

		// A dead-lettered job is never claimed again, no matter how much time passes.
		assertEquals(List.of(), reminderJobService.claimQueuedJobs(third.plus(Duration.ofDays(1)), 10));
	}

	@Test
	void permanentFailureDeadLettersImmediatelyWithoutRetry() {
		Long reminderId = createDueReminderAndEnqueueJob();
		sender.script(permanentFailure());

		Long jobId = claimSingleJob(T0);
		reminderJobService.processJob(jobId, T0);

		ReminderJob job = reloadJob(jobId);
		assertEquals(ReminderJobStatus.FAILED, job.getStatus());
		assertEquals(1, job.getAttemptCount());
		assertTrue(job.getLastError().contains("PermanentNotificationException"));
		assertEquals(ReminderStatus.FAILED, reloadReminder(reminderId).getStatus());
		assertEquals(1, sender.calls());
	}

	@Test
	void unexpectedExceptionIsTreatedAsTransient() {
		createDueReminderAndEnqueueJob();
		sender.script(() -> {
			throw new IllegalStateException("adapter bug");
		});

		Long jobId = claimSingleJob(T0);
		reminderJobService.processJob(jobId, T0);

		ReminderJob job = reloadJob(jobId);
		assertEquals(ReminderJobStatus.QUEUED, job.getStatus());
		assertEquals("IllegalStateException: adapter bug", job.getLastError());
	}

	@Test
	void recoversJobStuckInProcessingAfterTimeout() {
		createDueReminderAndEnqueueJob();
		Long jobId = claimSingleJob(T0);
		// Simulate a crash: the job was claimed but processJob never ran.

		Duration timeout = Duration.ofMinutes(5);
		assertEquals(0, reminderJobService.recoverStuckJobs(T0.plus(Duration.ofMinutes(4)), timeout, 10),
				"still within the lease: must not be reclaimed");

		Instant later = T0.plus(Duration.ofMinutes(6));
		assertEquals(1, reminderJobService.recoverStuckJobs(later, timeout, 10));

		ReminderJob job = reloadJob(jobId);
		assertEquals(ReminderJobStatus.QUEUED, job.getStatus());
		assertEquals(1, job.getAttemptCount(), "the crashed attempt still counts");
		assertEquals(later.plusSeconds(30), job.getNextAttemptAt());
		assertTrue(job.getLastError().startsWith("Processing timed out"));
	}

	@Test
	void processJobIgnoresJobThatIsNoLongerProcessing() {
		createDueReminderAndEnqueueJob();
		Long jobId = claimSingleJob(T0);
		// Recovery requeued the job before the original worker got round to processing it.
		reminderJobService.recoverStuckJobs(T0.plus(Duration.ofMinutes(10)), Duration.ofMinutes(5), 10);

		reminderJobService.processJob(jobId, T0.plus(Duration.ofMinutes(10)));

		assertEquals(0, sender.calls(), "no send without holding the PROCESSING claim");
		assertEquals(ReminderJobStatus.QUEUED, reloadJob(jobId).getStatus());
	}

	@Test
	void deletingReminderCascadesToItsJobs() {
		// V6: reminder_jobs.reminder_id is ON DELETE CASCADE. Before V6 this delete failed
		// with a foreign-key violation as soon as the scheduler had enqueued a job.
		Long reminderId = createDueReminderAndEnqueueJob();
		sender.script(permanentFailure());
		reminderJobService.processJob(claimSingleJob(T0), T0);
		assertEquals(1, reminderJobRepository.count());

		reminderService.deleteReminder(reminderId);

		assertTrue(reminderRepository.findById(reminderId).isEmpty());
		assertEquals(0, reminderJobRepository.count(), "jobs must be removed with their reminder");
	}

	// ── helpers ────────────────────────────────────────────────────────────────

	private Long createDueReminderAndEnqueueJob() {
		User user = userRepository.save(User.create("Ada Lovelace", "ada@example.com"));
		Reminder reminder = reminderRepository.save(Reminder.create(user, "Call John", "Discuss project", DUE));
		assertEquals(1, reminderClaimService.claimDueReminders(T0, 10));
		return reminder.getId();
	}

	private Long claimSingleJob(Instant now) {
		List<Long> ids = reminderJobService.claimQueuedJobs(now, 10);
		assertEquals(1, ids.size(), "expected exactly one claimable job at " + now);
		return ids.get(0);
	}

	private ReminderJob reloadJob(Long id) {
		return reminderJobRepository.findById(id).orElseThrow();
	}

	private Reminder reloadReminder(Long id) {
		return reminderRepository.findById(id).orElseThrow();
	}

	private static Runnable success() {
		return () -> { };
	}

	private static Runnable transientFailure() {
		return () -> {
			throw new TransientNotificationException("provider timed out");
		};
	}

	private static Runnable permanentFailure() {
		return () -> {
			throw new PermanentNotificationException("recipient address rejected");
		};
	}

	/**
	 * Test double for the notification port. Each call to {@link #send} runs the next scripted
	 * outcome; once the script is used up, sends succeed.
	 */
	static final class ScriptedSender implements NotificationSender {

		private final Deque<Runnable> outcomes = new ArrayDeque<>();
		private final AtomicInteger calls = new AtomicInteger();

		@Override
		public synchronized void send(ReminderNotification notification) {
			calls.incrementAndGet();
			Runnable next = outcomes.pollFirst();
			if (next != null) {
				next.run();
			}
		}

		synchronized void script(Runnable... steps) {
			outcomes.clear();
			outcomes.addAll(List.of(steps));
		}

		synchronized void reset() {
			outcomes.clear();
			calls.set(0);
		}

		int calls() {
			return calls.get();
		}

	}

	@TestConfiguration
	static class Config {

		/** {@code @Primary} wins over {@code ConsoleNotificationSender} for this test context. */
		@Bean
		@Primary
		ScriptedSender scriptedSender() {
			return new ScriptedSender();
		}

	}

}
