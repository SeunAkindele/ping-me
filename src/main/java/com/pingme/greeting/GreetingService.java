package com.pingme.greeting;

/**
 * Stage 1 — Interface-based dependency injection: controllers depend on this abstraction, not a
 * concrete class, which keeps the hello endpoint testable and swappable.
 */
public interface GreetingService {

	String greet();

}
