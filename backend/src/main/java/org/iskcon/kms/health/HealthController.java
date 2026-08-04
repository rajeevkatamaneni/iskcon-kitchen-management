package org.iskcon.kms.health;

import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal liveness endpoint for load balancer / uptime-monitor checks (E1-S1).
 *
 * <p>Deliberately separate from Spring Boot Actuator's richer {@code /actuator/health}
 * (which E1-S11 will extend with DB and job-scheduler checks) — this endpoint is the
 * simple, always-available "is the process up" signal the CI pipeline and Cloud Run
 * health checks depend on from day one.
 */
@RestController
public class HealthController {

	@GetMapping("/health")
	public Map<String, Object> health() {
		return Map.of(
				"status", "UP",
				"timestamp", Instant.now().toString());
	}
}
