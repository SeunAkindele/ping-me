package com.pingme;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the PingMe modular monolith.
 *
 * <p>Stage 1 — {@code @SpringBootApplication} enables component scanning, auto-configuration, and
 * embedded web server startup. {@link #main(String[])} delegates to Spring Boot to bootstrap the
 * application context (the IoC container that wires controllers, services, and repositories).
 *
 * <p>Stage 5 — {@code @EnableScheduling} turns on one background scheduler for the process.
 * Reminders stay rows in PostgreSQL; the scheduler claims due rows. It does not create a cron job
 * per user. {@code @ConfigurationPropertiesScan} binds {@code pingme.scheduler.*} at startup.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class PingMeApplication {

	public static void main(String[] args) {
		SpringApplication.run(PingMeApplication.class, args);
	}

}
