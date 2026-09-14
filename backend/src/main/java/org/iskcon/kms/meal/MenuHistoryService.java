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
 * <p>Two reads and nothing else: the most recent meal carrying this occasion name, and its dishes.
 * No new table, no copy of last year's menu kept anywhere: the meals <em>are</em> the record, and a
 * second copy of them would be a second thing to keep true.
 *
 * <p><strong>The meal is found once and then used by its id (D-27).</strong> The second read used to
 * find "that meal's preparations" by matching the first read's date and kind name again, which also
 * swept in any other meal of that kind on that day — two events on Janmashtami would have handed back
 * one menu made of both. It reads the dishes of the one meal row the first read chose.
 *
 * <p>It never writes. The menu is offered and one press puts it in, but the press is the planner's
 * and the composer does the writing through the ordinary save — so every rule that governs a planned
 * meal still governs a meal planned this way, the fast rule included.
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
	 * same occasion name the moment it is saved, and without this it would find itself.
	 *
	 * <p>Cancelled dishes are left out, and a meal every dish of which was cancelled is not a meal that
	 * was cooked. A dish whose recipe has since been archived or removed is counted and then skipped,
	 * so the composer can say <em>"2 of last year's 18 preparations are no longer in your recipes"</em>.
	 *
	 * <p><strong>The biggest meal of the day, where a day holds several.</strong> On a real Janmashtami
	 * every meal carries the occasion, and "what was cooked for Janmashtami" means the feast, so the tie
	 * is broken on the number of dishes rather than on the clock.
	 */
	@Transactional(readOnly = true)
	public MenuHistoryView lastMenuFor(String occasionName, LocalDate before) {
		String occasion = occasionName == null ? "" : occasionName.trim();
		if (occasion.isEmpty()) {
			return MenuHistoryView.none(occasion);
		}

		List<LastMeal> last = jdbc.query("""
				SELECT m.id, pd.plan_date, k.name AS meal_kind, m.occasion_name
				FROM meals m
				JOIN meal_plan_days pd ON pd.id = m.meal_plan_day_id
				JOIN meal_kinds k ON k.id = m.meal_kind_id
				JOIN meal_dishes d ON d.meal_id = m.id AND d.status <> 'CANCELLED'
				WHERE m.occasion_name IS NOT NULL
				  AND lower(btrim(m.occasion_name)) = lower(btrim(?))
				  AND pd.plan_date < ?
				GROUP BY m.id, pd.plan_date, k.name, m.occasion_name, m.ready_by
				ORDER BY pd.plan_date DESC, count(*) DESC, m.ready_by DESC
				LIMIT 1
				""", (rs, n) -> new LastMeal(
						rs.getObject("id", UUID.class),
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
				SELECT d.recipe_id, r.id AS found_id, r.name AS recipe_name, r.status AS recipe_status
				FROM meal_dishes d
				LEFT JOIN recipes r ON r.id = d.recipe_id
				WHERE d.meal_id = ? AND d.status <> 'CANCELLED'
				ORDER BY r.name NULLS LAST
				""", (rs, n) -> new Remembered(
						rs.getObject("recipe_id", UUID.class),
						rs.getObject("found_id", UUID.class) != null
								&& "ACTIVE".equals(rs.getString("recipe_status")),
						rs.getString("recipe_name")),
				meal.id());

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
				List.copyOf(available),
				meal.id());
	}

	private record LastMeal(UUID id, LocalDate planDate, String mealKind, String occasionName) {
	}

	private record Remembered(UUID recipeId, boolean stillAvailable, String recipeName) {
	}
}
