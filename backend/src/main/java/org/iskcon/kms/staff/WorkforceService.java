package org.iskcon.kms.staff;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.iskcon.kms.shift.ShiftService;
import org.iskcon.kms.shift.ShiftView;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * window covers that meal's ready-by time.
 *
 * <p>D-14 then split the volunteer half of that in two, because the clock was answering a question
 * it could not answer. A shift posted 06:00–10:00 to cut vegetables for lunch was counted toward
 * <em>breakfast</em>, breakfast being what is due at 08:00 — missing the lunch it was for and
 * inflating the breakfast it was not, and the inflation is the dangerous half, because a crew figure
 * that is quietly too high is one nobody checks. So:
 *
 * <ul>
 *   <li>A <strong>linked</strong> shift — one posted for a named meal — counts toward that meal and
 *       toward no other, whatever hours it runs.
 *   <li>An <strong>unlinked</strong> shift counts toward every meal whose ready-by its window spans,
 *       both ends inclusive, exactly as before.
 * </ul>
 *
 * <p>The second rule is not a fallback kept for old rows, though it does keep every one of them
 * reading as it did. It is a real distinction: an unlinked shift is a general offer of hands and the
 * clock is the right way to place it, while a linked shift is hands committed to one meal. "I can
 * help Saturday morning" and "I am coming in for Janmashtami lunch prep" are both things a temple
 * says, and it is also what keeps a 06:00–22:00 festival shift counting toward all three meals,
 * because the person really is there for all three.
 */
@Service
public class WorkforceService {

	private final ScheduleResolver resolver;
	private final ShiftService shiftService;
	private final JdbcTemplate jdbc;

	public WorkforceService(ScheduleResolver resolver, ShiftService shiftService, JdbcTemplate jdbc) {
		this.resolver = resolver;
		this.shiftService = shiftService;
		this.jdbc = jdbc;
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
	 *
	 * <p>The shift's own date. Since D-27 a meal shift's date is its meal's date, so for those the
	 * two agree; this is the day-grain figure — <em>who is in the building on Tuesday</em> — and the
	 * meal link answers a different question, read where that question is asked, in {@link #countAt}.
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
		// Not list(from, to): a shift for a meal in this range counts toward it by id, and is fetched
		// by the meal's date as well as its own so the two can never part silently (D-14, D-27).
		List<ShiftView> shifts = shiftService.listCountingTowardMeals(from, to);

		Map<MealMoment, WorkforceCount> counts = new LinkedHashMap<>();
		for (MealMoment moment : moments) {
			counts.put(moment, new WorkforceCount(
					moment.date(),
					resolution.staffIn(moment.date(), moment.readyBy(), staffAway),
					volunteersAt(shifts, moment)));
		}
		return counts;
	}

	// ---- Per kitchen, and by name (Epic 12) ------------------------------

	/**
	 * One member of staff who is in for a meal: who they are and which kitchen they work in.
	 *
	 * @param staffProfileId their staff record
	 * @param kitchenId      the kitchen on that record ({@code staff_profiles.kitchen_id}, V150); every
	 *                       staff member belongs to exactly one
	 * @param name           their name as the staff record spells it, which is what the planner prints
	 */
	public record RosteredPerson(UUID staffProfileId, UUID kitchenId, String name) {
	}

	/**
	 * Everybody in for one meal: the staff by name, in the roster's order, and the volunteers as a count.
	 *
	 * <p>The staff come as people rather than as a number because since Epic 12 the question is asked
	 * per kitchen. Rajeev, 2026-09-19: <em>"'People needed' is answered per kitchen; the rostered staff
	 * shown are that kitchen's staff."</em> The planner prints the names under each kitchen. Reading one
	 * list two ways (all of it, or one kitchen's slice) keeps the whole-meal figure and the kitchens'
	 * figures the same arithmetic on the same resolution, so they cannot drift apart.
	 *
	 * <p>Volunteers have no kitchen. A shift is posted for a meal or for a stretch of the day, never for
	 * a kitchen, so the count stays whole here and the caller decides which section it falls to.
	 *
	 * @param staff      in the roster's order, which is {@link ScheduleResolver}'s: by name
	 * @param volunteers counted exactly as {@link #countAt} counts them
	 */
	public record RosterAt(List<RosteredPerson> staff, int volunteers) {

		/** Every rostered name, in the roster's order. */
		public List<String> names() {
			return staff.stream().map(RosteredPerson::name).toList();
		}

		/** The names of the staff whose record is in this kitchen, in the roster's order. */
		public List<String> namesIn(UUID kitchenId) {
			return staff.stream().filter(p -> Objects.equals(p.kitchenId(), kitchenId))
					.map(RosteredPerson::name).toList();
		}
	}

	/**
	 * Who is in for each of these meals, by name and kitchen: the same rule as {@link #countAt} on the
	 * same resolution, answered as people instead of a head count (Epic 12).
	 *
	 * <p>A person is in for a meal exactly when {@link #countAt} would count them: an active staff
	 * record, a working day that is not half-day leave, and a window that covers the ready-by, both ends
	 * inclusive. Kitchens change nothing about that rule. What is added is only which kitchen the
	 * person's record is in, so the caller can slice the answer per kitchen.
	 *
	 * <p>The kitchen is read from {@code staff_profiles} here rather than from the resolved staff view,
	 * so this does not depend on how the employment screens choose to show it. It is one query over the
	 * temple's own staff; row-level security keeps another temple's out, as it does every other read.
	 *
	 * @param staffAway one staff record to leave out, as {@link #countAt(Collection, UUID)} does; null
	 *                  leaves nobody out
	 */
	@Transactional(readOnly = true)
	public Map<MealMoment, RosterAt> rosterAt(Collection<MealMoment> moments, UUID staffAway) {
		if (moments.isEmpty()) {
			return Map.of();
		}
		LocalDate from = moments.stream().map(MealMoment::date).min(LocalDate::compareTo).orElseThrow();
		LocalDate to = moments.stream().map(MealMoment::date).max(LocalDate::compareTo).orElseThrow();

		ScheduleResolver.Resolution resolution = resolver.resolve(from, to);
		List<ShiftView> shifts = shiftService.listCountingTowardMeals(from, to);
		Map<UUID, UUID> kitchenOf = new HashMap<>();
		jdbc.query("SELECT id, kitchen_id FROM staff_profiles WHERE employment_status = 'ACTIVE'", rs -> {
			kitchenOf.put(rs.getObject("id", UUID.class), rs.getObject("kitchen_id", UUID.class));
		});

		Map<MealMoment, RosterAt> rosters = new LinkedHashMap<>();
		for (MealMoment moment : moments) {
			List<RosteredPerson> in = new ArrayList<>();
			for (StaffProfileView person : resolution.staff()) {
				if (staffAway != null && staffAway.equals(person.id())) {
					continue;
				}
				ScheduleResolver.ResolvedShift shift =
						resolution.days().getOrDefault(person.id(), Map.of()).get(moment.date());
				// The same test Resolution.staffIn applies, person by person, so the names and the count
				// can never disagree about who is there.
				if (shift != null && shift.countsAsIn() && shift.covers(moment.readyBy())) {
					in.add(new RosteredPerson(person.id(), kitchenOf.get(person.id()), person.fullName()));
				}
			}
			rosters.put(moment, new RosterAt(List.copyOf(in), volunteersAt(shifts, moment)));
		}
		return rosters;
	}

	/**
	 * Volunteers standing in this meal's kitchen, by whichever of the two rules applies to the shift
	 * they signed up for (D-14).
	 *
	 * <p>A linked shift is asked one question — <em>is this the meal you were posted for?</em> — and
	 * the answer is the whole of it. Its hours are not consulted, which is the point: the shift that
	 * prompted the ruling runs 06:00–10:00 and is for a lunch served at 12:00, so any test of its
	 * window against the ready-by time would place it back on breakfast.
	 *
	 * <p>An unlinked shift is placed by the clock, as it always was: open on that date and running
	 * when the food is due, with both ends of the window inclusive — the same rule the staff side
	 * uses, because somebody signed up until 14:00 is in the kitchen at 14:00.
	 *
	 * <p>The matching itself is {@link MealMoment#isFor}, which since D-27 compares the shift's meal
	 * id with the meal's. Before D-27 it folded the case and spacing of three copied texts, and a link
	 * folded one character differently matched nothing while looking like a shift nobody signed up
	 * for; an id cannot be typed differently.
	 */
	private static int volunteersAt(List<ShiftView> shifts, MealMoment moment) {
		int in = 0;
		for (ShiftView shift : shifts) {
			if (shift.linkedToAMeal()) {
				if (moment.isFor(shift.mealId())) {
					in += shift.signedUpCount();
				}
				continue;
			}
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
