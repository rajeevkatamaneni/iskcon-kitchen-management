package org.iskcon.kms.staff;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.iskcon.kms.meal.MealCrewService;
import org.iskcon.kms.meal.MealCrewView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where the kitchen is short of hands, day by day (E6-S15).
 *
 * <p>Nothing here counts anybody. Both halves are already computed elsewhere and this only folds
 * them onto one line per day, which is the whole point: {@link WorkforceService} is the single
 * source for the roster (E6-S11 D5, E6-S14 D2) and {@link MealCrewService} is the single source for
 * what a meal takes and whether it has it (item 24). A second head count here — the obvious way to
 * write this class — would be a fifth opinion about how many cooks there are on the same Thursday,
 * and the one screen meant to settle the question would be the one that reopened it.
 *
 * <p><strong>Staff and volunteers are added for the shortfall and reported apart beside it.</strong>
 * That is not a new decision, it is the one already made: a meal is satisfied when staff plus
 * volunteers reaches the planned number and does not care which (E6-S14 D3 keeps them apart for
 * every other reader; {@code WorkforceCount.rostered()} is the one place they are legitimately
 * added, and this is that question). So the shortfall is a single number — <em>three short</em> —
 * and the day still says <em>4 staff, 2 volunteers</em> next to it, because a manager filling the
 * gap needs to know which kind of person is missing even though the meal does not.
 *
 * <p>Two grains meet here and it is deliberate. The roster figures are per <em>day</em>, because
 * that is what the column of a week grid means. The shortfall is per <em>meal</em>, because a cook
 * on 06:00–14:00 is not a pair of hands at dinner, and a day-grain comparison would report a
 * comfortable Tuesday whose dinner has nobody in the kitchen.
 */
@Service
public class CrewCoverageService {

	private final WorkforceService workforce;
	private final MealCrewService mealCrew;

	public CrewCoverageService(WorkforceService workforce, MealCrewService mealCrew) {
		this.workforce = workforce;
		this.mealCrew = mealCrew;
	}

	/**
	 * Every date in the inclusive range, in order, including the ones with nothing planned. A caller
	 * drawing seven columns or thirty rows needs an answer for each of them; making it discover that
	 * a missing date means "nothing owed" is how a Sunday ends up blank instead of quiet.
	 */
	@Transactional(readOnly = true)
	public List<DayCoverageView> coverage(LocalDate from, LocalDate to) {
		Map<LocalDate, WorkforceCount> roster = workforce.countFor(from, to);

		Map<LocalDate, List<MealCrewView>> mealsByDate = new LinkedHashMap<>();
		for (MealCrewView meal : mealCrew.crewFor(from, to)) {
			mealsByDate.computeIfAbsent(meal.planDate(), d -> new ArrayList<>()).add(meal);
		}

		List<DayCoverageView> days = new ArrayList<>();
		for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
			WorkforceCount count = roster.getOrDefault(date, new WorkforceCount(date, 0, 0));
			days.add(fold(date, count, mealsByDate.getOrDefault(date, List.of())));
		}
		return days;
	}

	/**
	 * One day's meals reduced to one sentence about that day.
	 *
	 * <p>The deepest shortfall wins, and ties go to the earlier meal — the list arrives in the order
	 * the kitchen works, so a breakfast and a dinner both three short name breakfast, which is the
	 * one that goes wrong first.
	 */
	private static DayCoverageView fold(LocalDate date, WorkforceCount roster, List<MealCrewView> meals) {
		int deepest = 0;
		MealCrewView worst = null;
		boolean anyRequirement = false;

		for (MealCrewView meal : meals) {
			if (meal.crewRequired() == null) {
				// Null is not zero. A meal nobody has crewed is not a meal that needs nobody, and
				// counting it as covered would be the screen inventing reassurance.
				continue;
			}
			anyRequirement = true;
			int shortBy = meal.crewRequired() - meal.rostered();
			if (shortBy > deepest) {
				deepest = shortBy;
				worst = meal;
			}
		}

		CoverageState state = meals.isEmpty()
				? CoverageState.NOTHING_PLANNED
				: !anyRequirement
						? CoverageState.CREW_NOT_SET
						: worst != null ? CoverageState.SHORT : CoverageState.COVERED;

		return new DayCoverageView(
				date,
				roster.staffIn(),
				roster.volunteers(),
				state,
				deepest,
				worst != null ? worst.mealKind() : null,
				worst != null ? worst.readyBy() : null,
				worst != null ? worst.crewRequired() : null,
				worst != null ? worst.rostered() : null);
	}
}
