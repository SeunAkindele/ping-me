package com.pingme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.time.Duration;
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
	void registersOneFixedDelayClaimTask() {
		assertEquals(1, scheduledTaskHolder.getScheduledTasks().size());
		ScheduledTask scheduledTask = scheduledTaskHolder.getScheduledTasks().iterator().next();
		FixedDelayTask task = assertInstanceOf(FixedDelayTask.class, scheduledTask.getTask());
		assertEquals(Duration.ofSeconds(30), task.getIntervalDuration());
		assertEquals(Duration.ZERO, task.getInitialDelayDuration());
	}

}
