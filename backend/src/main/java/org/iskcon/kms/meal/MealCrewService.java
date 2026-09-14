package org.iskcon.kms.meal;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.staff.MealMoment;
import org.iskcon.kms.staff.WorkforceCount;
import org.iskcon.kms.staff.WorkforceService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * How many people it takes to cook the meal, and how many there are (item 24).
 *
 * <p>The planner carries the number of people needed to execute a meal. At execution time that can
 * be any mix of staff and volunteers — it is satisfied when staff + volunteers reaches the planned
 * number — so there is one counter and not two. The mix does not matter, and splitting it would
 * invent a constraint the temple does not have.
 *
 * <p>The rostered side is asked per meal rather than per day, through {@link WorkforceService}: a
 * person counts towards a meal if their working window covers the time that meal's food must be
 * ready. A volunteer counts the same way — a shift posted 11:00–14:00 still falls to lunch without
 * anybody linking it to one — unless the shift is for a meal, in which case it counts toward that
 * meal and no other (D-14), matched by the meal's id since D-27. The clock was getting this wrong in both directions:
 * a shift 06:00–10:00 to cut vegetables for lunch was landing on breakfast, so lunch was short of
 * hands that were coming and breakfast was credited with hands that were not.
 *
 * <p>Nothing here refuses anything. A meal short of hands takes a quiet warning tone on the screen
 * and saves exactly as it would otherwise — a meal is planned weeks before anybody is rostered, and
 * a planner blocked in August by a roster nobody has written for September would simply stop using
 * the field.
 */
@Service
public class MealCrewService {

	private final JdbcTemplate jdbc;
	private final MealKindService mealKindService;
	private final ServedMealService servedMealService;
	private final WorkforceService workforceService;

	public MealCrewService(
			JdbcTemplate jdbc, MealKindService mealKindService, ServedMealService servedMealService,
			WorkforceService workforceService) {
		this.jdbc = jdbc;
		this.mealKindService = mealKindService;
		this.servedMealService = servedMealService;
		this.workforceService = workforceService;
	}

	// ---- The readout ----------------------------------------------------

	/**
	 * Every meal in the range with the hands it needs and the hands it has, in the order the kitchen
	 * works.
	 *
	 * <p>A meal every dish of which was called off is left out. It is not work the kitchen has to do,
	 * so it is not a crew it has to find either, and a cancelled lunch drawn in warning colours would
	 * be the screen worrying on the temple's behalf about nothing.
	 */
	@Transactional(readOnly = true)
	public List<MealCrewView> crewFor(LocalDate from, LocalDate to) {
		List<ServedMeal> meals = mealsIn(from, to);
		Map<MealMoment, WorkforceCount> counts = workforceService.countAt(momentsOf(meals));
		List<MealCrewView> readouts = new ArrayList<>();
		for (ServedMeal meal : meals) {
			readouts.add(readout(meal, counts.get(momentOf(meal))));
		}
		return readouts;
	}

	/**
	 * What approving one person's leave would cost each meal it covers — <em>"Approving this leaves
	 * Lunch on 24 Aug at 4 of 8."</em>
	 *
	 * <p>Only the meals the person is actually standing in for come back. A cook rostered 06:00–14:00
	 * costs breakfast and lunch nothing that dinner also loses, and listing dinner at the figure it
	 * already had would be three lines of noise around the one that matters.
	 *
	 * <p>The counts are the ones the approver would be left with, not the ones they have now. Told,
	 * never enforced: the decision is the approver's and this is only what it is going to cost.
	 */
	@Transactional(readOnly = true)
	public List<MealCrewView> crewIfAway(UUID staffProfileId, LocalDate from, LocalDate to) {
		List<ServedMeal> meals = mealsIn(from, to);
		if (meals.isEmpty()) {
			return List.of();
		}
		List<MealMoment> moments = momentsOf(meals);
		Map<MealMoment, WorkforceCount> asItStands = workforceService.countAt(moments);
		Map<MealMoment, WorkforceCount> withoutThem = workforceService.countAt(moments, staffProfileId);

		List<MealCrewView> affected = new ArrayList<>();
		for (ServedMeal meal : meals) {
			MealMoment moment = momentOf(meal);
			WorkforceCount before = asItStands.get(moment);
			WorkforceCount after = withoutThem.get(moment);
			if (before == null || after == null || before.staffIn() == after.staffIn()) {
				continue;
			}
			affected.add(readout(meal, after));
		}
		return affected;
	}

	// ---- A meal not saved yet (T-215) -----------------------------------

	/**
	 * Who is rostered at a date and ready-by that has no meal row yet: the figure the composer reads
	 * out, and prefills <em>Volunteers requested</em> from, before the meal's first save.
	 *
	 * <p><strong>Why this exists.</strong> Rajeev ruled (D-27 answer 1) that the planner's volunteer
	 * layer opens on People needed minus Rostered. The composer could only find Rostered on a meal the
	 * server had already seen, so a brand-new event, or a main meal with no meal of its kind that day,
	 * read "Not counted yet" and prefilled the whole of People needed. The browser test saw it: five
	 * asked for where two staff were rostered and three were short, and the same meal read "2 of 5"
	 * the moment it was saved.
	 *
	 * <p><strong>Counted the way a saved meal is counted, and nowhere else.</strong> The question goes
	 * through {@link WorkforceService#countAt} as a {@link MealMoment}, exactly as {@link #crewFor}
	 * asks it, so the figure before the first save and the figure after it are the same arithmetic on
	 * the same roster. A second count written here would be the first thing to drift.
	 *
	 * <p><strong>The moment has no meal id, and that decides the volunteers.</strong> A shift <em>for a
	 * meal</em> counts toward that meal and no other (D-14), matched by id; with no id,
	 * {@link MealMoment#isFor} is false for every one of them, so none counts here. A shift <em>not for
	 * a meal</em> counts if it is open on that date and its window covers the ready-by, both ends
	 * inclusive — the rule every meal already uses for those. That is also what the saved meal reads
	 * the moment it is saved: the only shift that could newly point at it is the one drafted with it,
	 * and nobody has signed up for that yet.
	 *
	 * <p>Read-only by construction, not by care: nothing here writes, and the transaction says so.
	 * Planning a meal saves nothing until Save this meal (D-27 answer 7), and asking who is rostered is
	 * not an exception to that.
	 */
	@Transactional(readOnly = true)
	public WorkforceCount crewAt(LocalDate date, LocalTime readyBy) {
		MealMoment moment = new MealMoment(null, date, readyBy, null, null);
		WorkforceCount count = workforceService.countAt(List.of(moment)).get(moment);
		return count != null ? count : new WorkforceCount(date, 0, 0);
	}

	// ---- The default the composer opens with ----------------------------

	/**
	 * What to pre-fill for a new meal of this kind: the median of the last three ordinary meals of it
	 * (Q11).
	 *
	 * <p>Nothing for the temple to maintain — no Settings field, no ratio to keep up to date. It
	 * learns the kitchen's real practice instead of asking for it. And deliberately not a formula off
	 * the servings: plates divided by a ratio is guesswork dressed as arithmetic, and it would be
	 * wrong in a way that looks authoritative.
	 *
	 * <p><strong>Ordinary</strong> means a stored {@code day_type} of REGULAR or WEEKEND. A festival
	 * lunch takes a crew no ordinary Tuesday will ever need, and letting one set the default would
	 * over-state every meal after it until somebody noticed.
	 *
	 * <p>That filter used to exclude CATERING too, and E4-S15 removed the value — the catering plans
	 * it excluded now read REGULAR or WEEKEND by their weekday, so they are inside this window where
	 * they were outside it. Checked rather than assumed: the median is taken per KIND, and those
	 * plans are Events now, so they can only ever move an Event's default. A Lunch still learns from
	 * lunches. And an Event learning what an event actually took is the answer we would want anyway.
	 *
	 * <p><strong>Three and not one</strong>, because the festival guard does not catch an unusual
	 * <em>ordinary</em> day. A visiting sannyasi, a wedding party: that meal is stored REGULAR, and
	 * as the last meal of its kind it would become the default for the next ordinary lunch. The
	 * middle of three throws it out.
	 *
	 * <p>The thin cases, in order: two meals give their mean rounded <em>up</em>, because being short
	 * is worse than being over; one meal gives itself; none gives null, and the field opens empty.
	 * Empty is honest. A made-up number would not be.
	 */
	@Transactional(readOnly = true)
	public Integer suggestedCrew(String mealKind) {
		// Through the kind service so an unknown kind is refused by name (KMS-400071) rather than
		// quietly matching no meals and reading as "this temple has never cooked one".
		UUID kindId = mealKindService.require(mealKind).id();
		// Since D-27 the crew figure is on the meal row and the day type on its day, and a meal is
		// "called off" when every dish of it is — the same test mealsIn() applies below. Matched on the
		// kind's id, never its name: a kind renamed in Settings keeps its history.
		List<Integer> recent = jdbc.queryForList("""
				SELECT m.crew_required
				FROM meals m
				JOIN meal_plan_days d ON d.id = m.meal_plan_day_id
				WHERE m.meal_kind_id = ?
				  AND d.day_type IN ('REGULAR', 'WEEKEND')
				  AND m.crew_required IS NOT NULL
				  AND EXISTS (SELECT 1 FROM meal_dishes md WHERE md.meal_id = m.id AND md.status <> 'CANCELLED')
				ORDER BY d.plan_date DESC, m.ready_by DESC
				LIMIT 3
				""", Integer.class, kindId);

		return median(recent);
	}

	/**
	 * The middle of three, the mean of two rounded up, the one of one, or null.
	 *
	 * <p>Package-private so the arithmetic can be tested for what it is, without a temple, a roster
	 * and three months of meals around it.
	 */
	static Integer median(List<Integer> values) {
		List<Integer> sorted = new ArrayList<>(values);
		sorted.sort(Integer::compareTo);
		return switch (sorted.size()) {
			case 0 -> null;
			case 1 -> sorted.get(0);
			// Rounded up, never down. Being short when you need more is the expensive mistake; an
			// extra pair of hands is not. The same asymmetry decides every close call in this feature.
			case 2 -> (sorted.get(0) + sorted.get(1) + 1) / 2;
			default -> sorted.get(sorted.size() / 2);
		};
	}

	// ---------------------------------------------------------------------

	private List<ServedMeal> mealsIn(LocalDate from, LocalDate to) {
		List<ServedMeal> meals = new ArrayList<>();
		for (ServedMeal meal : servedMealService.list(from, to)) {
			if (meal.dishes().stream().allMatch(d -> d.status() == MealStatus.CANCELLED)) {
				continue;
			}
			meals.add(meal);
		}
		return meals;
	}

	/**
	 * The moments to ask the roster about, without duplicates — the same meal asked about twice is one
	 * question, and the roster should be asked it once.
	 *
	 * <p>What counts as "the same" narrowed with D-14, and it had to. A moment used to be a date and
	 * a ready-by time, so two different meals due at the same minute collapsed into one question and
	 * one answer. That was harmless while the answer depended only on the clock; it is wrong now that
	 * a shift can be for one of them and not the other. A moment carries the meal's own id (D-27), so
	 * two meals sharing a minute are two questions with two answers.
	 */
	private static List<MealMoment> momentsOf(List<ServedMeal> meals) {
		Map<MealMoment, Boolean> seen = new LinkedHashMap<>();
		for (ServedMeal meal : meals) {
			seen.put(momentOf(meal), Boolean.TRUE);
		}
		return List.copyOf(seen.keySet());
	}

	/** The meal as the roster is asked about it, by its own id (D-27). */
	private static MealMoment momentOf(ServedMeal meal) {
		return new MealMoment(meal.mealId(), meal.planDate(), meal.readyBy(), meal.mealKind(), meal.eventName());
	}

	/**
	 * One meal beside one reading of the roster.
	 *
	 * <p>The two are added through {@link WorkforceCount#rostered()} rather than here, so that the one
	 * place in the product where a cook and a volunteer become interchangeable stays one place.
	 */
	private static MealCrewView readout(ServedMeal meal, WorkforceCount count) {
		WorkforceCount roster = count != null ? count : new WorkforceCount(meal.planDate(), 0, 0);
		return new MealCrewView(
				meal.mealId(),
				meal.planDate(),
				meal.mealKind(),
				meal.readyBy(),
				meal.crewRequired(),
				roster.staffIn(),
				roster.volunteers(),
				roster.rostered(),
				meal.crewRequired() != null && roster.rostered() < meal.crewRequired());
	}
}
