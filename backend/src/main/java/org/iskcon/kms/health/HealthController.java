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
 *
 * <p>It also reports whether a <b>background worker</b> is alive anywhere. Jobs run in their own
 * Cloud Run service, so this instance's own scheduler is deliberately on standby — which means "is
 * anything actually firing triggers?" cannot be answered from local state. Quartz's clustered job
 * store answers it: every running scheduler checks in to {@code qrtz_scheduler_state} on an
 * interval, so a recent check-in is proof a worker is alive, and a stale one is exactly the silent
 * failure E1-S11 exists to surface.
 */
@RestController
public class HealthController {

	/**
	 * How long a worker may go without checking in before we stop believing it is alive. Three times
	 * Quartz's 20-second cluster check-in, so one missed beat is tolerated and two are not.
	 */
	private static final int WORKER_STALE_AFTER_SECONDS = 60;

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
		body.put("worker", workerState());
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
	 * Whether a background worker is alive anywhere: RUNNING when one checked in within the window,
	 * STALE when the last check-in is older than that, ABSENT when none has ever run, UNKNOWN when
	 * the question itself could not be asked.
	 *
	 * <p>Deliberately not part of {@link #health()}'s 200/503 decision. A dead worker is serious, but
	 * it does not stop this instance serving requests, and failing the uptime check for it would page
	 * someone about the wrong thing and take the site "down" in every monitor watching this URL.
	 */
	private String workerState() {
		try {
			return workerStateFrom(jdbc.queryForObject(
					"SELECT EXTRACT(EPOCH FROM (now() - to_timestamp(max(last_checkin_time) / 1000)))::bigint"
							+ " FROM qrtz_scheduler_state",
					Long.class));
		} catch (Exception e) {
			return "UNKNOWN";
		}
	}

	/**
	 * How long since the most recent check-in becomes a state. Separate from the query because the
	 * boundary is the part worth testing exactly, and it cannot be tested through the database: the
	 * check-in table is shared by every scheduler on the cluster, so "nothing has checked in lately"
	 * is not a state a test can create while any scheduler is alive.
	 */
	static String workerStateFrom(Long secondsSinceCheckin) {
		if (secondsSinceCheckin == null) {
			return "ABSENT";
		}
		return secondsSinceCheckin <= WORKER_STALE_AFTER_SECONDS ? "RUNNING" : "STALE";
	}

	/**
	 * The scheduler only has to be RUNNING on an instance meant to run it (the worker). On the API,
	 * which enqueues but does not fire, STANDBY or ABSENT is exactly right.
	 */
	private boolean schedulerAcceptable(String state) {
		return !schedulerShouldRun || "RUNNING".equals(state);
	}
}
