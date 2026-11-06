package com.pingme.greeting;

import org.springframework.stereotype.Service;

/**
 * Stage 1 — Default {@link GreetingService} registered in the Spring context via {@code @Service}.
 */
@Service
public class DefaultGreetingService implements GreetingService {

	@Override
	public String greet() {
		return "Hello from PingMe";
	}

}
