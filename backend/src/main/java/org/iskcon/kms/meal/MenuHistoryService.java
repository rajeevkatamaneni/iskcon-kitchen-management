package org.iskcon.kms.meal;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the temple cooked for this festival last time (item 26b).
 *
 * <p>Two reads over {@code meal_plans} and nothing else. The most recent meal carrying this occasion
 * name, and its preparations. No new table, no new column, no copy of last year's menu kept
 * anywhere: the plans <em>are</em> the record, and a second copy of them would be a second thing to
 * keep true.
 *
 * <p>It never writes. The menu is offered and one press puts it in, but the press is the planner's
 * and the composer does the writing through the ordinary create path — so every rule that governs a
 * planned meal still governs a meal planned this way, the fast rule included.
 */
@Service
public class MenuHistoryService {

	private final JdbcTemplate jdbc;

	public MenuHistoryService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * The last meal cooked for this occasion before {@code before}, and what was in it.
	 *
	 * <p><strong>Before, and never on or after.</strong> The meal being planned right now carries the
	 * same occasion name the moment its first preparation is saved, and without this it would find
	 * itself and offer the planner back the three preparations they have just entered.
	 *
	 * <p>Cancelled preparations are left out. A preparation called off, in the plan or at the stove,
	 * is part of the record of what was decided but not of what was cooked, and this question is about
	 * what was cooked.
	 *
	 * <p>A preparation whose recipe has since been archived or removed is counted and then skipped.
	 * Recipes became removable in {@code cf629fe}, so a menu can genuinely come back shorter than it
	 * went in — and the count is what lets the composer say <em>"2 of last year's 18 preparations are
	 * no longer in your recipes"</em> rather than quietly handing over sixteen.
	 *
	 * <p><strong>The biggest meal of the day, where a day holds several.</strong> On a real
	 * Janmashtami every meal carries the occasion: the temple serves an ordinary breakfast, then the
	 * feast, and the derivation writes the same name onto all of them. "What was cooked for
	 * Janmashtami" means the feast, so the tie is broken on the number of preparations rather than on
	 * the clock. Breaking it on the clock would offer back a two-preparation dinner that happened to
	 * fall on the festival, which is the one menu nobody is trying to reassemble.
	 */
	@Transactional(readOnly = true)
	public MenuHistoryView lastMenuFor(String occasionName, LocalDate before) {
		String occasion = occasionName == null ? "" : occasionName.trim();
		if (occasion.isEmpty()) {
			return MenuHistoryView.none(occasion);
		}

		List<LastMeal> last = jdbc.query("""
				SELECT plan_date, meal_kind, max(occasion_name) AS occasion_name
				FROM meal_plans
				WHERE occasion_name IS NOT NULL
				  AND lower(btrim(occasion_name)) = lower(btrim(?))
				  AND plan_date < ?
				  AND status <> 'CANCELLED'
				GROUP BY plan_date, meal_kind
				ORDER BY plan_date DESC, count(*) DESC, max(ready_by) DESC
				LIMIT 1
				""", (rs, n) -> new LastMeal(
						rs.getObject("plan_date", LocalDate.class),
						rs.getString("meal_kind"),
						rs.getString("occasion_name")),
				occasion, before);

		if (last.isEmpty()) {
			return MenuHistoryView.none(occasion);
		}
		LastMeal meal = last.get(0);

		// LEFT JOIN, not JOIN: the whole point is to be able to count what is gone. An inner join
		// would return sixteen preparations and no way to know eighteen were cooked.
		List<Remembered> remembered = jdbc.query("""
				SELECT mp.recipe_id, r.id AS found_id, r.name AS recipe_name, r.status AS recipe_status
				FROM meal_plans mp
				LEFT JOIN recipes r ON r.id = mp.recipe_id
				WHERE mp.plan_date = ? AND mp.meal_kind = ? AND mp.status <> 'CANCELLED'
				ORDER BY r.name NULLS LAST
				""", (rs, n) -> new Remembered(
						rs.getObject("recipe_id", UUID.class),
						rs.getObject("found_id", UUID.class) != null
								&& "ACTIVE".equals(rs.getString("recipe_status")),
						rs.getString("recipe_name")),
				meal.planDate(), meal.mealKind());

		List<MenuHistoryView.Preparation> available = new ArrayList<>();
		int missing = 0;
		for (Remembered dish : remembered) {
			if (dish.stillAvailable()) {
				available.add(new MenuHistoryView.Preparation(dish.recipeId(), dish.recipeName()));
			} else {
				missing++;
			}
		}

		return new MenuHistoryView(
				// The occasion as it was spelled on the meal that was actually cooked, not as the
				// caller typed it. "janmashtami" asked for it; "Janmashtami" is what the sentence says.
				meal.occasionName(),
				meal.planDate(),
				meal.mealKind(),
				remembered.size(),
				missing,
				List.copyOf(available));
	}

	private record LastMeal(LocalDate planDate, String mealKind, String occasionName) {
	}

	private record Remembered(UUID recipeId, boolean stillAvailable, String recipeName) {
	}
}
