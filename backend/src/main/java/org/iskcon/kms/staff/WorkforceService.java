package org.iskcon.kms.staff;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.iskcon.kms.shift.ShiftService;
import org.iskcon.kms.shift.ShiftView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * How much of a kitchen there is, on a date (B1/B3, build brief §6b).
 *
 * <p>One question — <em>is there enough of a kitchen to cook with?</em> — answered in one place, and
 * that is the whole reason this class exists rather than each screen counting for itself. The foot
 * of the week grid, the Workforce tile on Today and the pebbles on the meal planner all read this,
 * so if they ever disagree it is a bug in one number rather than an argument between three.
 *
 * <p>The staff half comes from {@link ScheduleResolver}, which is also what draws the grid: template,
 * adjusted by any per-date override, minus approved leave, and active employment only. The
 * volunteer half comes from the shifts they signed up for. The two are reported side by side and
 * never added — a full-time cook and a two-hour evening volunteer are not interchangeable, and a
 * single figure of "seven" would hide which seven.
 */
@Service
public class WorkforceService {

	private final ScheduleResolver resolver;
	private final ShiftService shiftService;

	public WorkforceService(ScheduleResolver resolver, ShiftService shiftService) {
		this.resolver = resolver;
		this.shiftService = shiftService;
	}

	@Transactional(readOnly = true)
	public WorkforceCount countFor(LocalDate date) {
		return countFor(date, date).get(date);
	}

	/**
	 * Every date in the inclusive range, in order, including the ones nobody is in on. A caller
	 * drawing seven columns needs seven answers; making it discover that a missing key means zero is
	 * how a Sunday ends up blank instead of empty.
	 */
	@Transactional(readOnly = true)
	public Map<LocalDate, WorkforceCount> countFor(LocalDate from, LocalDate to) {
		ScheduleResolver.Resolution resolution = resolver.resolve(from, to);
		Map<LocalDate, Integer> volunteers = volunteersByDate(from, to);

		Map<LocalDate, WorkforceCount> counts = new LinkedHashMap<>();
		for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
			counts.put(date, new WorkforceCount(
					date, resolution.staffIn(date), volunteers.getOrDefault(date, 0)));
		}
		return counts;
	}

	/** The same figures the grid's own resolution has already computed, without resolving twice. */
	Map<LocalDate, WorkforceCount> countFor(
			LocalDate from, LocalDate to, ScheduleResolver.Resolution resolution) {

		Map<LocalDate, Integer> volunteers = volunteersByDate(from, to);
		Map<LocalDate, WorkforceCount> counts = new LinkedHashMap<>();
		for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
			counts.put(date, new WorkforceCount(
					date, resolution.staffIn(date), volunteers.getOrDefault(date, 0)));
		}
		return counts;
	}

	/**
	 * Volunteers signed up for a shift falling on each date. Cancelled shifts are excluded — nobody
	 * is coming to one — and a devotee who took two shifts on one day counts twice, because the
	 * question is how many pairs of hands turn up, not how many people the temple knows.
	 */
	private Map<LocalDate, Integer> volunteersByDate(LocalDate from, LocalDate to) {
		Map<LocalDate, Integer> byDate = new LinkedHashMap<>();
		for (ShiftView shift : shiftService.list(from, to, false)) {
			byDate.merge(shift.shiftDate(), shift.signedUpCount(), Integer::sum);
		}
		return byDate;
	}

	/** The shape the HTTP layer serves: the range in date order. */
	@Transactional(readOnly = true)
	public List<WorkforceCount> listFor(LocalDate from, LocalDate to) {
		return List.copyOf(countFor(from, to).values());
	}
}
