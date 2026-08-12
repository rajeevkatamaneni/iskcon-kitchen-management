package org.iskcon.kms.health;

import static org.assertj.core.api.Assertions.assertThat;

import org.iskcon.kms.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * "Is anything actually running the jobs?" — answered from the API, which does not run them.
 *
 * <p>Jobs live in their own service, so the API's own scheduler is on standby by design and tells an
 * operator nothing. The clustered Quartz job store is what makes the question answerable at all:
 * every live scheduler checks in to {@code qrtz_scheduler_state}, so this reads the freshness of
 * that check-in. Verified against a real database because the whole signal is a database row.
 *
 * <p>What is <em>not</em> here: the stale case. That table is shared by every scheduler on the
 * cluster, including the ones other test contexts keep alive, so "nothing has checked in lately"
 * cannot be staged from a test. The window itself is pinned in {@link WorkerStateTest} instead.
 */
class WorkerLivenessIT extends AbstractIntegrationTest {

	@Autowired
	private TestRestTemplate testRestTemplate;

	@Autowired
	private JdbcTemplate jdbc;

	@LocalServerPort
	private int port;

	@BeforeEach
	@AfterEach
	void clearCheckins() {
		jdbc.update("DELETE FROM qrtz_scheduler_state WHERE instance_name LIKE 'test-worker%'");
	}

	@Test
	@DisplayName("a worker that checked in a moment ago reads as running")
	void recentCheckinIsRunning() {
		checkIn("test-worker-1", System.currentTimeMillis());

		assertThat(health()).contains("\"worker\":\"RUNNING\"");
	}

	@Test
	@DisplayName("the API stays healthy when no worker is alive — a dead worker is not a dead site")
	void aDeadWorkerDoesNotFailTheUptimeCheck() {
		// Nothing has ever checked in: the state table is empty for this scheduler.
		String body = health();

		assertThat(body).as("reported, so an operator can see it").contains("\"worker\":");
		assertThat(body).as("but the instance itself is still up").contains("\"status\":\"UP\"");
	}

	private void checkIn(String instance, long atEpochMillis) {
		jdbc.update("""
				INSERT INTO qrtz_scheduler_state (sched_name, instance_name, last_checkin_time, checkin_interval)
				VALUES ('kms-scheduler', ?, ?, 20000)
				ON CONFLICT (sched_name, instance_name) DO UPDATE SET last_checkin_time = EXCLUDED.last_checkin_time
				""", instance, atEpochMillis);
	}

	private String health() {
		return testRestTemplate.getForEntity("http://localhost:" + port + "/health", String.class).getBody();
	}
}
