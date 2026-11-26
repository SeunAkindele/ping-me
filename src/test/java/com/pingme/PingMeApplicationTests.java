package com.pingme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class PingMeApplicationTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private ScheduledTaskHolder scheduledTaskHolder;

	@Test
	void contextLoads() {
	}

	@Test
	void registersFixedDelaySchedulerAndWorkerTasks() {
		// Stage 6: two background loops — scheduler (producer, 30s) and worker (consumer, 10s).
		List<Duration> intervals = scheduledTaskHolder.getScheduledTasks().stream()
				.map(ScheduledTask::getTask)
				.map(task -> assertInstanceOf(FixedDelayTask.class, task))
				.peek(task -> assertEquals(Duration.ZERO, task.getInitialDelayDuration()))
				.map(FixedDelayTask::getIntervalDuration)
				.sorted()
				.toList();
		assertEquals(List.of(Duration.ofSeconds(10), Duration.ofSeconds(30)), intervals);
	}

}
