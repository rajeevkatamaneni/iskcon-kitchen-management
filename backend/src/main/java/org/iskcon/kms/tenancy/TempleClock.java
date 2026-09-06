package org.iskcon.kms.tenancy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * What day it is where the food is being cooked (2026-09-05).
 *
 * <p>Rajeev: <em>"ALL Date and Time values for that Temple MUST be in that Time zone irrespective of
 * where the Temples dedicated tenant is being accessed from."</em> Twenty-odd services each carried
 * their own {@code ZoneId.of("Asia/Kolkata")} — correct for every temple onboarded so far, and wrong
 * in twenty places at once for the first one that is not. Two of them, {@code MealPlanService} and
 * {@code CalendarService}, already read the temple's own zone, so the codebase held both the right
 * pattern and twenty copies of the wrong one.
 *
 * <p><strong>Nothing is derived from coordinates.</strong> {@code tenants.timezone} has been asked
 * for at provisioning since V1. A zone worked out from a latitude would be a guess standing in for
 * an answer the temple has already given, and it would be wrong near a border for exactly the
 * temples least able to tell.
 *
 * <p><strong>Not cached.</strong> One indexed read by primary key, the same query
 * {@code MealPlanService} has always made. A cache would have to be invalidated when a temple
 * corrects its zone, and a stale answer here is a whole temple reading the wrong day — a poor trade
 * for a lookup Postgres answers out of shared buffers.
 */
@Service
public class TempleClock {

	/**
	 * The zone the platform itself keeps, and the fallback when there is no temple in scope.
	 *
	 * <p>Deliberately not the server's default. A platform operator comparing two temples wants one
	 * clock, and a JVM's zone is a fact about a container rather than about anybody's kitchen — the
	 * bug that dated a Bengaluru order to the previous day when the server ran in UTC.
	 */
	public static final ZoneId PLATFORM_ZONE = ZoneId.of("Asia/Kolkata");

	private final JdbcTemplate jdbc;

	public TempleClock(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * The current temple's zone, or the platform's where there is no temple or no answer.
	 *
	 * <p>Three things arrive at that fallback and all three mean the same: a platform operator, who
	 * belongs to no temple; a request outside any tenant context; and a stored zone the JVM does not
	 * recognise, which is a data fault that must not take a screen down.
	 */
	public ZoneId zone() {
		List<String> stored = jdbc.query("""
				SELECT timezone FROM tenants
				WHERE id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""", (rs, n) -> rs.getString("timezone"));
		if (stored.isEmpty() || stored.get(0) == null || stored.get(0).isBlank()) {
			return PLATFORM_ZONE;
		}
		try {
			return ZoneId.of(stored.get(0));
		} catch (RuntimeException e) {
			return PLATFORM_ZONE;
		}
	}

	/** The temple's today — the operational day, which is the only day a kitchen has. */
	public LocalDate today() {
		return LocalDate.now(zone());
	}

	/** The day an instant fell on, where the temple is. */
	public LocalDate dayOf(Instant instant) {
		return instant.atZone(zone()).toLocalDate();
	}
}
