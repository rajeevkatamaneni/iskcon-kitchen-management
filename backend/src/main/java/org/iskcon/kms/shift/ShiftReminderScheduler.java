package org.iskcon.kms.shift;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.iskcon.kms.jobs.KmsJob;
import org.iskcon.kms.tenancy.TenantContext;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.impl.matchers.GroupMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Schedules, reschedules, and cancels the per-(signup × offset) reminder jobs (E6-S6). Jobs are
 * grouped per shift so a shift edit or cancellation can clear them in one sweep; each is named by
 * signup and offset so a single release cancels exactly its own.
 *
 * <p>Every interaction is best-effort: a scheduler that is unavailable (no worker on this node) or a
 * hiccup while scheduling must never fail the signup or release that triggered it. A volunteer who
 * signs up after an offset has already passed simply gets no job for that offset.
 */
@Component
public class ShiftReminderScheduler {

	private static final Logger log = LoggerFactory.getLogger(ShiftReminderScheduler.class);
	private static final ZoneId TEMPLE_ZONE = ZoneId.of("Asia/Kolkata");

	private final ObjectProvider<Scheduler> scheduler;
	private final JdbcTemplate jdbc;
	private final ObjectMapper objectMapper;

	public ShiftReminderScheduler(
			ObjectProvider<Scheduler> scheduler, JdbcTemplate jdbc, ObjectMapper objectMapper) {
		this.scheduler = scheduler;
		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
	}

	/**
	 * Schedules a reminder for each future offset of an active signup. Returns the offsets actually
	 * scheduled — offsets whose fire time has already passed are skipped.
	 */
	public List<Integer> scheduleForSignup(UUID signupId) {
		Scheduler quartz = scheduler.getIfAvailable();
		UUID tenantId = TenantContext.get().orElse(null);
		if (quartz == null || tenantId == null) {
			return List.of();
		}
		Map<String, Object> shift;
		try {
			shift = jdbc.queryForMap("""
					SELECT ss.shift_id, s.status, s.shift_date, s.start_time, s.reminder_offsets_minutes
					FROM shift_signups ss JOIN shifts s ON s.id = ss.shift_id
					WHERE ss.id = ? AND ss.released_at IS NULL
					""", signupId);
		} catch (EmptyResultDataAccessException e) {
			return List.of();
		}
		if (!"OPEN".equals(shift.get("status"))) {
			return List.of();
		}
		UUID shiftId = (UUID) shift.get("shift_id");
		Instant start = LocalDateTime.of(
				((java.sql.Date) shift.get("shift_date")).toLocalDate(),
				((java.sql.Time) shift.get("start_time")).toLocalTime()).atZone(TEMPLE_ZONE).toInstant();

		List<Integer> scheduled = new ArrayList<>();
		for (int offset : parseOffsets(shift.get("reminder_offsets_minutes"))) {
			Instant fire = start.minusSeconds(offset * 60L);
			if (fire.isAfter(Instant.now())) {
				if (schedule(quartz, tenantId, shiftId, signupId, offset, fire)) {
					scheduled.add(offset);
				}
			}
		}
		return scheduled;
	}

	/** Cancels every reminder for one signup (E6-S4 release). */
	public void cancelForSignup(UUID shiftId, UUID signupId) {
		forEachJob(shiftId, key -> {
			if (key.getName().startsWith(signupId + ":")) {
				deleteQuietly(key);
			}
		});
	}

	/** Cancels every reminder for a shift (E6-S2 cancellation). */
	public void cancelForShift(UUID shiftId) {
		forEachJob(shiftId, this::deleteQuietly);
	}

	/** Clears and re-schedules a shift's reminders after an edit (time or offset change, E6-S6). */
	public void rescheduleForShift(UUID shiftId) {
		cancelForShift(shiftId);
		List<UUID> active = jdbc.queryForList(
				"SELECT id FROM shift_signups WHERE shift_id = ? AND released_at IS NULL", UUID.class, shiftId);
		for (UUID signupId : active) {
			scheduleForSignup(signupId);
		}
	}

	// ---------------------------------------------------------------------

	private boolean schedule(Scheduler quartz, UUID tenantId, UUID shiftId, UUID signupId, int offset,
			Instant fire) {
		try {
			JobDetail job = JobBuilder.newJob(SendShiftReminderJob.class)
					.withIdentity(jobName(signupId, offset), group(shiftId))
					.usingJobData(SendShiftReminderJob.SIGNUP_ID_KEY, signupId.toString())
					.usingJobData(SendShiftReminderJob.OFFSET_KEY, offset)
					.usingJobData(KmsJob.TENANT_KEY, tenantId.toString())
					.requestRecovery()
					.build();
			Trigger trigger = TriggerBuilder.newTrigger().forJob(job).startAt(Date.from(fire)).build();
			quartz.scheduleJob(job, trigger);
			return true;
		} catch (SchedulerException e) {
			log.warn("Could not schedule a reminder for signup {} at offset {}: {}", signupId, offset, e.toString());
			return false;
		}
	}

	private void forEachJob(UUID shiftId, java.util.function.Consumer<JobKey> action) {
		Scheduler quartz = scheduler.getIfAvailable();
		if (quartz == null) {
			return;
		}
		try {
			Set<JobKey> keys = quartz.getJobKeys(GroupMatcher.jobGroupEquals(group(shiftId)));
			if (keys != null) {
				keys.forEach(action);
			}
		} catch (SchedulerException e) {
			log.warn("Could not enumerate reminder jobs for shift {}: {}", shiftId, e.toString());
		}
	}

	private void deleteQuietly(JobKey key) {
		Scheduler quartz = scheduler.getIfAvailable();
		if (quartz == null) {
			return;
		}
		try {
			quartz.deleteJob(key);
		} catch (SchedulerException e) {
			log.warn("Could not delete reminder job {}: {}", key, e.toString());
		}
	}

	private List<Integer> parseOffsets(Object jsonb) {
		if (jsonb == null) {
			return List.of(1440);
		}
		try {
			return objectMapper.readValue(jsonb.toString(), new TypeReference<List<Integer>>() {
			});
		} catch (Exception e) {
			return List.of(1440);
		}
	}

	private static String group(UUID shiftId) {
		return "shiftrem-" + shiftId;
	}

	private static String jobName(UUID signupId, int offset) {
		return signupId + ":" + offset;
	}
}
