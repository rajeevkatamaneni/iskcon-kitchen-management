package org.iskcon.kms.health;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness for the external uptime monitor (E1-S11). Deliberately simple and public: a single
 * endpoint that answers "can this instance actually do its job" — the database is reachable, and
 * the scheduler is running where it is meant to be — with a 200 when healthy and a 503 when not, so
 * a plain HTTP ping (with phone/WhatsApp alerting) is all the monitor needs.
 *
 * <p>Kept separate from Actuator's richer {@code /actuator/health}; this is the always-available,
 * dependency-light signal the load balancer and monitor depend on.
 */
@RestController
public class HealthController {

	private final JdbcTemplate jdbc;
	private final ObjectProvider<Scheduler> scheduler;
	private final boolean schedulerShouldRun;

	public HealthController(
			JdbcTemplate jdbc,
			ObjectProvider<Scheduler> scheduler,
			@Value("${spring.quartz.auto-startup:false}") boolean schedulerShouldRun) {
		this.jdbc = jdbc;
		this.scheduler = scheduler;
		this.schedulerShouldRun = schedulerShouldRun;
	}

	@GetMapping("/health")
	public ResponseEntity<Map<String, Object>> health() {
		boolean dbUp = databaseReachable();
		String schedulerState = schedulerState();
		boolean healthy = dbUp && schedulerAcceptable(schedulerState);

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("status", healthy ? "UP" : "DOWN");
		body.put("db", dbUp ? "UP" : "DOWN");
		body.put("scheduler", schedulerState);
		body.put("timestamp", Instant.now().toString());

		return ResponseEntity.status(healthy ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
	}

	private boolean databaseReachable() {
		try {
			jdbc.queryForObject("SELECT 1", Integer.class);
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	/** RUNNING, STANDBY, ABSENT (no scheduler on this instance), or ERROR. */
	private String schedulerState() {
		Scheduler quartz = scheduler.getIfAvailable();
		if (quartz == null) {
			return "ABSENT";
		}
		try {
			return quartz.isStarted() && !quartz.isInStandbyMode() ? "RUNNING" : "STANDBY";
		} catch (SchedulerException e) {
			return "ERROR";
		}
	}

	/**
	 * The scheduler only has to be RUNNING on an instance meant to run it (the worker). On the API,
	 * which enqueues but does not fire, STANDBY or ABSENT is exactly right.
	 */
	private boolean schedulerAcceptable(String state) {
		return !schedulerShouldRun || "RUNNING".equals(state);
	}
}
