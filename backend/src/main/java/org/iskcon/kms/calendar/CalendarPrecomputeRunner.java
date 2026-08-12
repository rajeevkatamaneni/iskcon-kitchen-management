package org.iskcon.kms.calendar;

import java.util.List;
import java.util.UUID;
import org.iskcon.kms.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The nightly sweep that keeps every temple's calendar 18 months ahead (E4-S1). Enumerates active
 * tenants at the platform level (the {@code tenants} registry isn't tenant-scoped) and precomputes
 * each in its own transaction across a bean boundary. One temple failing is logged and skipped,
 * never allowed to sink the rest of the sweep — the same shape as the low-stock digest sweep.
 */
@Component
public class CalendarPrecomputeRunner {

	private static final Logger log = LoggerFactory.getLogger(CalendarPrecomputeRunner.class);

	private final JdbcTemplate jdbc;
	private final CalendarService calendarService;

	public CalendarPrecomputeRunner(JdbcTemplate jdbc, CalendarService calendarService) {
		this.jdbc = jdbc;
		this.calendarService = calendarService;
	}

	/** Precomputes the calendar for every active tenant. Returns how many temples were refreshed. */
	public int sweep() {
		List<UUID> tenants = jdbc.query(
				"SELECT id FROM tenants",
				(rs, n) -> rs.getObject("id", UUID.class));

		int done = 0;
		for (UUID tenantId : tenants) {
			try {
				TenantContext.set(tenantId);
				calendarService.precomputeForCurrentTenant();
				done++;
			} catch (RuntimeException e) {
				log.warn("Calendar precompute failed for tenant {}: {}", tenantId, e.toString());
			} finally {
				TenantContext.clear();
			}
		}
		return done;
	}
}
