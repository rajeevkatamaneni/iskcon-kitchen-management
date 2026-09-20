package org.iskcon.kms.kitchen;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Which kitchen is whose, and in what order a person sees the kitchens on a meal (Epic 12, T-350).
 *
 * <p><strong>Why one class.</strong> Three questions have the same shape and must give the same
 * answer everywhere they are asked: which kitchen a person works in, which kitchen a meal goes to when
 * nobody said, and which section of a meal a person sees first. The meal screen, the job card, the crew
 * figures, Today and the planner guard all ask them. Written once here, they cannot drift apart —
 * which is exactly what would happen if each package ordered its own sections "the way Settings does".
 *
 * <p><strong>The rules, as Rajeev set them on 2026-09-19</strong> (docs/work/DISPATCH.md, Epic 12):
 * <ul>
 *   <li>A person's kitchen is the one on their current staff record — employment ACTIVE. A former
 *       employee, a devotee, or anybody with no staff record has none.
 *   <li>A meal's sections read with the viewer's own kitchen first when it is on the meal; else the
 *       main kitchen first when it is on the meal; then the rest in Settings order.
 *   <li>Settings order is {@code is_main DESC, lower(name)} over ACTIVE kitchens, with archived ones
 *       after, by name. An archived kitchen can still be on an old meal, so it still needs a place.
 *   <li>A meal with no kitchen named goes to the saver's kitchen if it plans meals here, else the main
 *       kitchen if it does, else the first planner kitchen in Settings order.
 *   <li>The planner is open to the Temple Admin, and otherwise only to someone whose kitchen is ACTIVE
 *       and uses the meal planner. No staff record, or a kitchen that only draws from the store, is a
 *       no (coordinator's correction to T-350, 2026-09-19): a person with no kitchen has no kitchen to
 *       plan for, and the main-kitchen fallback belongs to the ordering rule, not to access.
 * </ul>
 *
 * <p><strong>Tenant.</strong> Every query runs through the request's {@link JdbcTemplate}, so row-level
 * security confines it to the caller's temple like every other service; a user id from another temple
 * simply finds no staff record. Nothing here takes a tenant id, on purpose.
 *
 * <p>The ordering and the decisions are pure static methods over {@link KitchenRef}s, so they are
 * tested without a database ({@code KitchenOrderTest}); the instance methods only fetch what they
 * need and hand it over.
 */
@Component
public class KitchenOrder {

	/**
	 * One kitchen, as much of it as ordering and deciding need.
	 *
	 * @param id              the kitchen's id
	 * @param name            its name as the temple wrote it
	 * @param isMain          whether it is the temple's main kitchen (at most one is)
	 * @param usesMealPlanner whether it plans its meals in the planner (V76)
	 * @param active          false when the kitchen is ARCHIVED
	 */
	public record KitchenRef(UUID id, String name, boolean isMain, boolean usesMealPlanner, boolean active) {

		/** Whether a meal may be planned for this kitchen: it is open and it uses the planner. */
		public boolean plansMeals() {
			return active && usesMealPlanner;
		}
	}

	private final JdbcTemplate jdbc;

	public KitchenOrder(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	// ---- Reading --------------------------------------------------------

	/**
	 * The kitchen on this user's current staff record, or empty when they have none — not employed
	 * here, employment ended, or a devotee. At most one row can match: {@code staff_profiles_one_per_user}
	 * is unique per temple and user.
	 *
	 * @param userId the person's {@code users.id}; null gives empty
	 */
	public Optional<UUID> kitchenOf(UUID userId) {
		if (userId == null) {
			return Optional.empty();
		}
		return jdbc.queryForList("""
				SELECT kitchen_id FROM staff_profiles
				WHERE user_id = ? AND employment_status = 'ACTIVE'
				""", UUID.class, userId).stream().findFirst();
	}

	/**
	 * Every kitchen of the temple, archived ones included, in Settings order: ACTIVE first as
	 * {@code is_main DESC, lower(name)}, then ARCHIVED by name. The id breaks a tie so the order is
	 * total, never "whatever the planner returned today".
	 */
	public List<KitchenRef> settingsOrder() {
		return jdbc.query("""
				SELECT id, name, is_main, uses_meal_planner, status = 'ACTIVE' AS active
				FROM kitchens
				ORDER BY (status = 'ACTIVE') DESC,
						 CASE WHEN status = 'ACTIVE' AND is_main THEN 0 ELSE 1 END,
						 lower(name), id
				""", (rs, n) -> new KitchenRef(
						rs.getObject("id", UUID.class),
						rs.getString("name"),
						rs.getBoolean("is_main"),
						rs.getBoolean("uses_meal_planner"),
						rs.getBoolean("active")));
	}

	/**
	 * The kitchen a meal goes to when the person saving it named none: their own kitchen if it plans
	 * meals here, else the main kitchen if it does, else the first planner kitchen in Settings order.
	 * Empty only when the temple has no ACTIVE kitchen using the planner at all.
	 *
	 * @param userId the person saving; null (or somebody with no kitchen) skips the first rule
	 */
	public Optional<UUID> defaultPlanningKitchen(UUID userId) {
		return defaultPlanningKitchen(kitchenOf(userId).orElse(null), settingsOrder());
	}

	/**
	 * Whether this person may use the meal planner, on top of {@code MANAGE_MEAL_PLANS}: always for the
	 * Temple Admin; otherwise only when their current staff record names a kitchen that is ACTIVE and
	 * uses the meal planner. No staff record is a no.
	 *
	 * @param userId       the person
	 * @param templeAdmin  whether their role is TEMPLE_ADMIN — passed in rather than looked up, because
	 *                     the caller already holds it on the authenticated user
	 */
	public boolean mayPlan(UUID userId, boolean templeAdmin) {
		if (templeAdmin) {
			return true;
		}
		return mayPlan(false, kitchenOf(userId).orElse(null), settingsOrder());
	}

	// ---- The rules, pure --------------------------------------------------

	/**
	 * Orders a meal's sections for one viewer: their own kitchen first if it is on the meal; else the
	 * main kitchen first if it is on the meal; then the rest in Settings order. A section whose kitchen
	 * is not in {@code settingsOrder} at all (it should not happen — the list includes archived
	 * kitchens) goes last, in the order it came.
	 *
	 * @param sections       whatever the caller holds per kitchen — a view, a crew figure, an id
	 * @param kitchenIdOf    how to read the kitchen id from one section
	 * @param viewerKitchen  the viewer's own kitchen ({@link #kitchenOf}), or null for none
	 * @param settingsOrder  {@link #settingsOrder()}
	 * @return a new list, the same sections, reordered
	 */
	public static <T> List<T> forViewer(
			Collection<T> sections, Function<T, UUID> kitchenIdOf, UUID viewerKitchen,
			List<KitchenRef> settingsOrder) {
		Map<UUID, Integer> rank = new java.util.HashMap<>();
		UUID main = null;
		for (int i = 0; i < settingsOrder.size(); i++) {
			KitchenRef k = settingsOrder.get(i);
			rank.put(k.id(), i);
			if (k.isMain()) {
				main = k.id();
			}
		}
		Set<UUID> onMeal = sections.stream().map(kitchenIdOf).collect(Collectors.toSet());
		UUID first = viewerKitchen != null && onMeal.contains(viewerKitchen) ? viewerKitchen
				: main != null && onMeal.contains(main) ? main
				: null;
		final UUID lead = first;

		List<T> out = new ArrayList<>(sections);
		// A stable sort, so sections outside the Settings list keep the order they came in.
		out.sort(Comparator.comparingInt((T s) -> kitchenIdOf.apply(s).equals(lead) ? 0 : 1)
				.thenComparingInt(s -> rank.getOrDefault(kitchenIdOf.apply(s), Integer.MAX_VALUE)));
		return out;
	}

	/** {@link #forViewer(Collection, Function, UUID, List)} over bare kitchen ids. */
	public static List<UUID> forViewer(Collection<UUID> kitchenIds, UUID viewerKitchen, List<KitchenRef> settingsOrder) {
		return forViewer(new LinkedHashSet<>(kitchenIds), Function.identity(), viewerKitchen, settingsOrder);
	}

	/**
	 * The default planning kitchen, decided from what {@link #defaultPlanningKitchen(UUID)} fetched.
	 *
	 * @param viewerKitchen the saver's own kitchen, or null
	 * @param settingsOrder {@link #settingsOrder()}; its order is what "first" means
	 */
	public static Optional<UUID> defaultPlanningKitchen(UUID viewerKitchen, List<KitchenRef> settingsOrder) {
		if (viewerKitchen != null) {
			Optional<KitchenRef> own = settingsOrder.stream().filter(k -> k.id().equals(viewerKitchen)).findFirst();
			if (own.isPresent() && own.get().plansMeals()) {
				return Optional.of(viewerKitchen);
			}
		}
		// Settings order puts an ACTIVE main kitchen first, so "the main kitchen if it plans meals, else
		// the first planner kitchen" is one pass.
		return settingsOrder.stream().filter(KitchenRef::plansMeals).map(KitchenRef::id).findFirst();
	}

	/**
	 * The planner decision, from what {@link #mayPlan(UUID, boolean)} fetched.
	 *
	 * @param templeAdmin   whether the person is the Temple Admin
	 * @param ownKitchen    the kitchen on their current staff record, or null for none
	 * @param settingsOrder {@link #settingsOrder()}, to know whether that kitchen plans meals
	 */
	public static boolean mayPlan(boolean templeAdmin, UUID ownKitchen, List<KitchenRef> settingsOrder) {
		if (templeAdmin) {
			return true;
		}
		if (ownKitchen == null) {
			return false;
		}
		return settingsOrder.stream()
				.filter(k -> k.id().equals(ownKitchen))
				.findFirst()
				.map(KitchenRef::plansMeals)
				.orElse(false);
	}
}
