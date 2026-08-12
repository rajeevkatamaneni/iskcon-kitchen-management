package org.iskcon.kms.health;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * When a background worker stops being believed alive.
 *
 * <p>The window is three of Quartz's twenty-second cluster check-ins, so one missed beat is
 * tolerated and two are not — tight enough that a dead worker is noticed within a minute, loose
 * enough that a slow one is not reported dead every time it pauses. The boundary matters, and it is
 * exactly what an integration test cannot pin down: the check-in table is shared by every scheduler
 * on the cluster, so no test can create "nothing has checked in lately" while one is alive.
 */
class WorkerStateTest {

	@Test
	@DisplayName("no worker has ever checked in")
	void neverStarted() {
		assertThat(HealthController.workerStateFrom(null)).isEqualTo("ABSENT");
	}

	@Test
	@DisplayName("a worker that checked in just now is running")
	void justCheckedIn() {
		assertThat(HealthController.workerStateFrom(0L)).isEqualTo("RUNNING");
	}

	@Test
	@DisplayName("a worker that missed one check-in is still running, not reported dead")
	void oneMissedBeatIsTolerated() {
		assertThat(HealthController.workerStateFrom(59L)).isEqualTo("RUNNING");
		assertThat(HealthController.workerStateFrom(60L)).isEqualTo("RUNNING");
	}

	@Test
	@DisplayName("past the window, the worker is stale — the silent failure, made visible")
	void pastTheWindowIsStale() {
		assertThat(HealthController.workerStateFrom(61L)).isEqualTo("STALE");
		assertThat(HealthController.workerStateFrom(3600L)).isEqualTo("STALE");
	}
}
