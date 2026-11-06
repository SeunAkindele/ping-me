package com.pingme.api;

import com.pingme.greeting.GreetingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stage 1 — Minimal REST endpoint demonstrating dependency injection.
 *
 * <p>{@link GreetingService} is injected via constructor; Spring supplies the {@code @Service}
 * implementation at runtime. The controller only maps HTTP to a response DTO.
 */
@RestController
public class HelloController {

	private final GreetingService greetingService;

	public HelloController(GreetingService greetingService) {
		this.greetingService = greetingService;
	}

	@GetMapping("/hello")
	public HelloResponse hello() {
		return new HelloResponse(greetingService.greet());
	}

}
