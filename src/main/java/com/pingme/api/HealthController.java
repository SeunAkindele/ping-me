package com.pingme.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stage 1 — Liveness probe used by load balancers and orchestrators to verify the process is up.
 *
 * <p>Intentionally lightweight: no database checks (those belong in a separate readiness probe
 * once external dependencies are required for serving traffic).
 */
@RestController
public class HealthController {

	@GetMapping("/health")
	public HealthResponse health() {
		return new HealthResponse("UP");
	}

}
