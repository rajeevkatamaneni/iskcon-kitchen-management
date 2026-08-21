package org.iskcon.kms.meal;

import java.time.LocalDate;
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
 * ready, and a volunteer counts the same way against the window of the shift they signed up for. So
 * a shift posted 11:00–14:00 falls to lunch without anybody having to link it to one.
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
		// Through the kind service so an unknown kind is refused by name (KMS-4942) rather than
		// quietly matching no meals and reading as "this temple has never cooked one".
		String kind = mealKindService.require(mealKind).name();
		List<Integer> recent = jdbc.queryForList("""
				SELECT crew_required FROM (
					SELECT plan_date, max(crew_required) AS crew_required
					FROM meal_plans
					WHERE meal_kind = ?
					  AND day_type IN ('REGULAR', 'WEEKEND')
					  AND status <> 'CANCELLED'
					  AND crew_required IS NOT NULL
					GROUP BY plan_date
				) meal
				ORDER BY plan_date DESC
				LIMIT 3
				""", Integer.class, kind);

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
	 * The moments to ask the roster about, without duplicates — two meals due at the same minute on
	 * the same day are one question, and the roster should be asked it once.
	 */
	private static List<MealMoment> momentsOf(List<ServedMeal> meals) {
		Map<MealMoment, Boolean> seen = new LinkedHashMap<>();
		for (ServedMeal meal : meals) {
			seen.put(momentOf(meal), Boolean.TRUE);
		}
		return List.copyOf(seen.keySet());
	}

	private static MealMoment momentOf(ServedMeal meal) {
		return new MealMoment(meal.planDate(), meal.readyBy());
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
