package org.iskcon.kms.assistant.api;

import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.tenancy.TempleClock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two daily limits on assistant runs: per person and per temple (E10-T11, plan §1 row D, §9).
 *
 * <p><strong>Counted in runs, not money.</strong> The runner signs in with a subscription, so a run
 * is not billed and {@code --max-budget-usd} means nothing (plan §8). What runs out is the plan's
 * usage allowance, and the number of runs is the lever the api actually holds.
 *
 * <p><strong>A run counts from the moment it starts.</strong> The run row is written {@code RUNNING}
 * in the same transaction that passed this check (V160's header gives the reason: a row written
 * only at the end would let ten simultaneous messages all pass a limit of one). Failed and timed-out
 * runs count too. They used the allowance as much as a good one did, and a person retrying a run
 * that keeps failing should not be able to do it without end.
 *
 * <p><strong>Two requests at once.</strong> Counting and inserting are two statements, so two
 * messages from the same temple arriving together could both count 499 and both start. A
 * transaction-scoped advisory lock on the temple makes the second wait for the first to commit its
 * row. It is held for the length of one short transaction (count, insert two rows), never across the
 * run itself, and it is per temple, so one temple's traffic never waits on another's.
 *
 * <p><strong>"Today" is the temple's day</strong> ({@link TempleClock}), so the allowance renews at
 * the temple's midnight, which is when the error text's "tomorrow" starts for the person reading it.
 *
 * <p>The temple's limit is checked first. If both are spent, the temple's is the true answer: nobody
 * there can use the assistant, and raising one person's limit would not help.
 */
@Component
public class AssistantBudget {

	private final JdbcTemplate jdbc;
	private final TempleClock clock;
	private final AssistantSettings settings;
	private final MeterRegistry meters;

	public AssistantBudget(JdbcTemplate jdbc, TempleClock clock, AssistantSettings settings, MeterRegistry meters) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.settings = settings;
		this.meters = meters;
	}

	/**
	 * Refuses with KMS-400193 or KMS-400192 if either limit is spent. Must run inside the transaction
	 * that then inserts the run row, so the lock it takes covers both.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void check(UUID tenantId, UUID userId) {
		jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
				rs -> { }, "assistant-budget:" + tenantId);

		Instant dayStart = clock.today().atStartOfDay(clock.zone()).toInstant();
		Timestamp since = Timestamp.from(dayStart);

		// RLS limits both counts to this temple; the WHERE adds only the day and the person.
		Integer templeRuns = jdbc.queryForObject(
				"SELECT count(*) FROM assistant_runs WHERE started_at >= ?", Integer.class, since);
		if (templeRuns != null && templeRuns >= settings.perTempleDailyLimit()) {
			refuse(ErrorCode.ASSISTANT_TEMPLE_DAILY_LIMIT_REACHED, "temple", templeRuns, settings.perTempleDailyLimit());
		}

		Integer personRuns = jdbc.queryForObject(
				"SELECT count(*) FROM assistant_runs WHERE started_at >= ? AND user_id = ?",
				Integer.class, since, userId);
		if (personRuns != null && personRuns >= settings.perPersonDailyLimit()) {
			refuse(ErrorCode.ASSISTANT_YOUR_DAILY_LIMIT_REACHED, "person", personRuns, settings.perPersonDailyLimit());
		}
	}

	private void refuse(ErrorCode code, String scope, int used, int limit) {
		meters.counter("assistant.budget_refusals", "scope", scope).increment();
		throw new ApplicationException(code, Map.of("scope", scope, "runsToday", used, "limit", limit));
	}
}
