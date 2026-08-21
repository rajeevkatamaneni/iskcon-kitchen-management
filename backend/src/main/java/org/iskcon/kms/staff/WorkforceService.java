package org.iskcon.kms.staff;

import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * never added <em>here</em> — a full-time cook and a two-hour evening volunteer are not
 * interchangeable, and a single figure of "seven" would hide which seven. Whether there are enough
 * of them for a particular meal is a different question, and the one place the two <em>are</em> added
 * is the answer to it: a meal takes a number of pairs of hands and does not care whose (item 24).
 *
 * <p>Two things changed with item 19. Somebody on half-day leave no longer counts, for the reasons
 * set out on {@link ScheduleResolver} — chiefly that the record does not say which half. And the
 * count now answers per meal as well as per day: a person counts towards a meal if their working
 * window covers that meal's ready-by time, and a volunteer counts the same way against the window of
 * the shift they signed up for. A shift posted 11:00–14:00 falls to lunch without anybody having to
 * link it to one.
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

	// ---- Per meal, not per day (item 19) --------------------------------

	/**
	 * Who is in for each of these meals: the staff whose working window covers the moment the food
	 * must be ready, and the volunteers whose shift window does.
	 *
	 * <p>A batch rather than one call per meal, because the roster is one query for a range whichever
	 * way it is asked, and a planner drawing a month would otherwise resolve it thirty times over.
	 * The moments may sit anywhere in the range; the span between the earliest and the latest is
	 * resolved once and every moment answered from it.
	 *
	 * <p>Every moment asked about comes back, including the ones nobody is in for. A caller drawing
	 * a readout beside each meal needs an answer beside each meal, and making it discover that a
	 * missing key means zero is how a meal ends up with a blank pebble instead of a warning one.
	 */
	@Transactional(readOnly = true)
	public Map<MealMoment, WorkforceCount> countAt(Collection<MealMoment> moments) {
		return countAt(moments, null);
	}

	/**
	 * The same figures as they would read with one member of staff away — what approving their leave
	 * would cost each meal it covers (item 24).
	 *
	 * <p>Told to the approver, never used to stop them. A temple that cannot spare somebody still has
	 * to let them go to a wedding, and a system that refused would only teach people not to ask.
	 */
	@Transactional(readOnly = true)
	public Map<MealMoment, WorkforceCount> countAt(Collection<MealMoment> moments, UUID staffAway) {
		if (moments.isEmpty()) {
			return Map.of();
		}
		LocalDate from = moments.stream().map(MealMoment::date).min(LocalDate::compareTo).orElseThrow();
		LocalDate to = moments.stream().map(MealMoment::date).max(LocalDate::compareTo).orElseThrow();

		ScheduleResolver.Resolution resolution = resolver.resolve(from, to);
		List<ShiftView> shifts = shiftService.list(from, to, false);

		Map<MealMoment, WorkforceCount> counts = new LinkedHashMap<>();
		for (MealMoment moment : moments) {
			counts.put(moment, new WorkforceCount(
					moment.date(),
					resolution.staffIn(moment.date(), moment.readyBy(), staffAway),
					volunteersAt(shifts, moment)));
		}
		return counts;
	}

	/**
	 * Volunteers whose shift is open on that date and running when the food is due. Both ends of the
	 * window are inclusive, the same rule the staff side uses: somebody signed up until 14:00 is in
	 * the kitchen at 14:00.
	 */
	private static int volunteersAt(List<ShiftView> shifts, MealMoment moment) {
		int in = 0;
		for (ShiftView shift : shifts) {
			if (!shift.shiftDate().equals(moment.date())) {
				continue;
			}
			if (moment.readyBy() != null
					&& (moment.readyBy().isBefore(shift.startTime()) || moment.readyBy().isAfter(shift.endTime()))) {
				continue;
			}
			in += shift.signedUpCount();
		}
		return in;
	}
}
