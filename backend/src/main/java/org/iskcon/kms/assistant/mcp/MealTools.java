package org.iskcon.kms.assistant.mcp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.iskcon.kms.assistant.action.PurchaseOrderAction;
import org.iskcon.kms.assistant.names.ConversationNames;
import org.iskcon.kms.assistant.names.ConversationNames.Names;
import org.iskcon.kms.calendar.CalendarDayView;
import org.iskcon.kms.calendar.CalendarService;
import org.iskcon.kms.ingredient.Unit;
import org.iskcon.kms.meal.DayContext;
import org.iskcon.kms.meal.IngredientShortfall;
import org.iskcon.kms.meal.MealDishView;
import org.iskcon.kms.meal.MealKindService;
import org.iskcon.kms.meal.MealKindView;
import org.iskcon.kms.meal.MealKitchenView;
import org.iskcon.kms.meal.MealPlanService;
import org.iskcon.kms.meal.MealSufficiency;
import org.iskcon.kms.meal.ServedMeal;
import org.iskcon.kms.meal.ShortfallItem;
import org.iskcon.kms.meal.SufficiencyService;
import org.iskcon.kms.recipe.RecipeService;
import org.iskcon.kms.recipe.RecipeSummary;
import org.iskcon.kms.tenancy.TempleClock;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

/**
 * The assistant's read-only meal tools (E10-MEAL-READ; plan §11 S7 and the read half of S8):
 * the meals planned, a day's calendar facts, whether the store covers the planned dishes, the total
 * shortfall, and the two lookups the meal planner will need to turn "breakfast" and "idli" into ids.
 *
 * <h2>Which services, and why these</h2>
 *
 * <p>Each tool calls the service the planner's own endpoint calls, so the assistant cannot disagree
 * with the screen: {@link MealPlanService#meals} behind {@code GET /meals},
 * {@link MealPlanService#dayContext} behind {@code GET /meal-plans/day-context},
 * {@link SufficiencyService#sufficiency} and {@link SufficiencyService#shortfallFeed} behind the
 * sufficiency badges and the shortfall feed, {@link MealKindService#list} behind the kinds list, and
 * {@link RecipeService#list} behind the recipe picker. The day-context answer adds the Vaishnava
 * calendar's own facts for the day ({@link CalendarService#day}: the Ekadashi's name, the fast, the
 * festivals), which the planner shows from the same service on its calendar strip.
 *
 * <p>Nothing here queries a table and nothing writes.
 *
 * <h2>The permission</h2>
 *
 * <p>The one the screen declares: {@code MANAGE_MEAL_PLANS} for everything about meals, and
 * {@code MANAGE_RECIPES} for the recipe search, exactly as {@code GET /recipes} declares it. The agent
 * token carries the signed-in person's own permissions (T5), so a volunteer, who cannot open the
 * planner, cannot have it read out either.
 *
 * <h2>Dates</h2>
 *
 * <p>Every date is the temple's: "today" is {@link TempleClock#today()}, in the temple's own time
 * zone, never the server's. Every answer carries {@code today} and each date's weekday, because the
 * model is resolving "tomorrow" and "next Sunday" and is better told the calendar than left to work
 * it out. Ranges are cut at {@value #MAX_DAYS} days and the answer says so, rather than refused: a
 * model told "too long" spends a turn, one told "here are the first 14 days" can say so.
 *
 * <h2>Names</h2>
 *
 * <p>Ingredient names (in the shortfalls) follow {@link ConversationNames} exactly as the stock tools
 * do: {@code name_local} from the temple's glossary, or {@code name_pending: true} with a fill asked
 * for. <strong>Dish names do not have a fill.</strong> The stock-name fill names inventory items only,
 * and the one store of translated recipes ({@code recipe_translations}) is a cache written when
 * somebody opens a translated recipe, keyed on the recipe's version, so it holds a name for some
 * dishes in some languages and nothing for the rest. So a dish carries {@code name_local} only where
 * the temple has typed one into its glossary, and otherwise only its English name, which the model is
 * told to use as written. Never {@code name_pending} on a dish, because nothing would ever fill it,
 * and the model would be told indefinitely to make up a translation for every dish.
 */
@Component
public class MealTools {

	/** The longest range one call reads; the planner's own week view is seven. */
	static final int MAX_DAYS = 14;

	/** Meals in one answer. Three meals and an event a day for 14 days is 56; a busy fortnight fits. */
	static final int MAX_MEALS = 60;

	/** Dish rows in one sufficiency answer. */
	static final int MAX_DISHES = 80;

	/** Ingredients in one shortfall answer, as the stock tools. */
	static final int MAX_SHORTFALL = 50;

	/** Recipes in one search answer: enough to pick from, few enough to read. */
	static final int MAX_RECIPES = 10;

	private final MealPlanService mealPlanService;
	private final SufficiencyService sufficiencyService;
	private final MealKindService mealKindService;
	private final RecipeService recipeService;
	private final CalendarService calendarService;
	private final TempleClock clock;
	private final ConversationNames names;

	public MealTools(
			MealPlanService mealPlanService, SufficiencyService sufficiencyService, MealKindService mealKindService,
			RecipeService recipeService, CalendarService calendarService, TempleClock clock, ConversationNames names) {
		this.mealPlanService = mealPlanService;
		this.sufficiencyService = sufficiencyService;
		this.mealKindService = mealKindService;
		this.recipeService = recipeService;
		this.calendarService = calendarService;
		this.clock = clock;
		this.names = names;
	}

	// ---- get_meals ------------------------------------------------------

	/** The meals planned from {@code from} to {@code to} (one day when {@code to} is absent). */
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public Map<String, Object> getMeals(String from, String to) {
		LocalDate today = clock.today();
		Range range = Range.of(from, to, today);
		Names dishNames = names.forCurrentAgent();

		List<ServedMeal> meals = mealPlanService.meals(range.from(), range.to());
		List<Map<String, Object>> rows = new ArrayList<>();
		for (ServedMeal meal : meals) {
			if (rows.size() >= MAX_MEALS) {
				break;
			}
			rows.add(mealRow(meal, dishNames));
		}
		Map<String, Object> result = range.head(today);
		result.put("meals", rows);
		result.put("returned", rows.size());
		result.put("total_meals", meals.size());
		result.put("truncated", meals.size() > rows.size());
		return result;
	}

	private static Map<String, Object> mealRow(ServedMeal meal, Names dishNames) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("meal_id", meal.mealId());
		row.put("date", meal.planDate());
		row.put("weekday", weekday(meal.planDate()));
		row.put("meal_kind", meal.mealKind());
		putIfPresent(row, "event_name", meal.eventName());
		putIfPresent(row, "occasion", meal.occasionName());
		putIfPresent(row, "ready_by", time(meal.readyBy()));
		row.put("status", meal.status() == null ? null : meal.status().name().toLowerCase(Locale.ROOT));
		putIfPresent(row, "adults", meal.adults());
		putIfPresent(row, "children", meal.children());
		putIfPresent(row, "seniors", meal.seniors());
		row.put("plates", meal.plates());
		List<MealKitchenView> kitchens = meal.kitchens() == null ? List.of() : meal.kitchens();
		row.put("kitchens", kitchens.stream().map(MealKitchenView::kitchenName).toList());
		putIfPresent(row, "people_needed", meal.crewRequired());
		row.put("recorded", meal.recorded());
		if (meal.isOutside()) {
			row.put("going_outside", true);
		}
		Map<UUID, String> kitchenNames = new HashMap<>();
		kitchens.forEach(k -> kitchenNames.put(k.kitchenId(), k.kitchenName()));
		List<Map<String, Object>> dishes = new ArrayList<>();
		for (MealDishView dish : meal.dishes()) {
			Map<String, Object> d = new LinkedHashMap<>();
			d.put("name", dish.recipeName());
			putDishLocal(d, dish.recipeName(), dishNames);
			Shown amount = Shown.of(dish.targetYield(), dish.targetYieldUnit());
			d.put("amount", amount.value());
			d.put("unit", amount.unit());
			d.put("status", dish.status() == null ? null : dish.status().name().toLowerCase(Locale.ROOT));
			// Which kitchen only matters when there is a choice: a meal cooked in one kitchen says so
			// once, on the meal.
			if (kitchens.size() > 1) {
				putIfPresent(d, "kitchen", kitchenNames.get(dish.kitchenId()));
			}
			if (dish.actualServings() != null) {
				d.put("cooked", Shown.of(dish.actualServings(), dish.targetYieldUnit()).value());
			}
			dishes.add(d);
		}
		row.put("dishes", dishes);
		if (meal.volunteerShift() != null) {
			Map<String, Object> shift = new LinkedHashMap<>();
			shift.put("volunteers_needed", meal.volunteerShift().capacity());
			shift.put("signed_up", meal.volunteerShift().signedUpCount());
			row.put("volunteer_shift", shift);
		}
		return row;
	}

	// ---- get_day_context -------------------------------------------------

	/** The calendar facts for one day, and the meals already planned on it. {@code date} absent is today. */
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public Map<String, Object> getDayContext(String date) {
		LocalDate today = clock.today();
		LocalDate day = date == null ? today : parse(date, "date");
		DayContext context = mealPlanService.dayContext(day);
		CalendarDayView calendar = calendarService.day(day).orElse(null);
		Names dishNames = names.forCurrentAgent();

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("today", today);
		result.put("today_weekday", weekday(today));
		result.put("date", day);
		result.put("weekday", weekday(day));
		result.put("is_ekadashi", context.isEkadashi());
		if (calendar != null) {
			if (calendar.isEkadashi()) {
				putIfPresent(result, "ekadashi_name", calendar.ekadashiName());
			}
			putIfPresent(result, "fast", calendar.fastType());
			List<String> festivals = calendar.festivals() == null ? List.of()
					: calendar.festivals().stream().map(CalendarDayView.CalendarFestivalView::text).toList();
			result.put("festivals", festivals);
		} else {
			result.put("festivals", List.of());
		}
		putIfPresent(result, "occasion", context.occasionName());
		result.put("day_type", context.suggestedDayType() == null ? null
				: context.suggestedDayType().name().toLowerCase(Locale.ROOT));
		putIfPresent(result, "usual_servings", context.suggestedServings());

		List<Map<String, Object>> meals = new ArrayList<>();
		for (ServedMeal meal : mealPlanService.meals(day, day)) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("meal_id", meal.mealId());
			m.put("meal_kind", meal.mealKind());
			putIfPresent(m, "event_name", meal.eventName());
			putIfPresent(m, "ready_by", time(meal.readyBy()));
			m.put("status", meal.status() == null ? null : meal.status().name().toLowerCase(Locale.ROOT));
			m.put("plates", meal.plates());
			List<Object> dishes = new ArrayList<>();
			for (MealDishView dish : meal.dishes()) {
				Map<String, Object> d = new LinkedHashMap<>();
				d.put("name", dish.recipeName());
				putDishLocal(d, dish.recipeName(), dishNames);
				dishes.add(d);
			}
			m.put("dishes", dishes);
			meals.add(m);
		}
		result.put("meals", meals);
		return result;
	}

	// ---- check_meal_sufficiency -----------------------------------------

	/** Per planned dish: covered by the store, short (of what, by how much, order by when), or not checked. */
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public Map<String, Object> checkMealSufficiency(String from, String to) {
		LocalDate today = clock.today();
		Range range = Range.of(from, to, today);
		Names local = names.forCurrentAgent();
		Names dishNames = names.forCurrentAgent();

		List<MealSufficiency> all = sufficiencyService.sufficiency(range.from(), range.to());
		List<Map<String, Object>> rows = new ArrayList<>();
		int enough = 0;
		int shortCount = 0;
		int notChecked = 0;
		for (MealSufficiency dish : all) {
			String status = status(dish);
			switch (status) {
				case "enough" -> enough++;
				case "short" -> shortCount++;
				default -> notChecked++;
			}
			if (rows.size() >= MAX_DISHES) {
				continue;
			}
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("date", dish.planDate());
			row.put("weekday", weekday(dish.planDate()));
			row.put("meal_kind", dish.mealKind());
			putIfPresent(row, "event_name", dish.eventName());
			putIfPresent(row, "ready_by", time(dish.readyBy()));
			row.put("dish", dish.recipeName());
			putDishLocal(row, dish.recipeName(), dishNames, "dish_local");
			row.put("status", status);
			if (dish.shortfalls() != null && !dish.shortfalls().isEmpty()) {
				List<Map<String, Object>> shortfalls = new ArrayList<>();
				for (IngredientShortfall s : dish.shortfalls()) {
					Map<String, Object> line = new LinkedHashMap<>();
					line.put("name", s.ingredientName());
					local.putInto(line, s.ingredientName());
					// One unit for all three figures, the one the gap reads in, so "needs 12 Kg, has
					// 800 gm" never reaches a cook.
					Shown shortBy = Shown.of(s.shortBy(), s.unit());
					line.put("needed", Shown.in(s.required(), s.unit(), shortBy));
					line.put("available", Shown.in(s.available(), s.unit(), shortBy));
					line.put("short_by", shortBy.value());
					line.put("unit", shortBy.unit());
					shortfalls.add(line);
				}
				row.put("short_of", shortfalls);
			}
			putIfPresent(row, "order_by", dish.orderBy());
			putIfPresent(row, "order_urgency",
					dish.orderUrgency() == null ? null : dish.orderUrgency().name().toLowerCase(Locale.ROOT));
			rows.add(row);
		}
		Map<String, Object> result = range.head(today);
		result.put("dishes", rows);
		result.put("dishes_enough", enough);
		result.put("dishes_short", shortCount);
		result.put("dishes_not_checked", notChecked);
		result.put("returned", rows.size());
		result.put("total_dishes", all.size());
		result.put("truncated", all.size() > rows.size());
		local.fillIfMissing();
		return result;
	}

	private static String status(MealSufficiency dish) {
		if (dish.status() == null) {
			return "not_checked";
		}
		return switch (dish.status()) {
			case SUFFICIENT -> "enough";
			case SHORT -> "short";
			case PLANNING -> "not_checked";
		};
	}

	// ---- get_shortfall ---------------------------------------------------

	/** Every ingredient the planned meals in the buying window are short of, summed across them. */
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public Map<String, Object> getShortfall() {
		LocalDate today = clock.today();
		Names local = names.forCurrentAgent();
		List<ShortfallItem> all = sufficiencyService.shortfallFeed();
		List<Map<String, Object>> rows = new ArrayList<>();
		for (ShortfallItem item : all) {
			if (rows.size() >= MAX_SHORTFALL) {
				break;
			}
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("name", item.ingredientName());
			local.putInto(row, item.ingredientName());
			Shown shortBy = Shown.of(item.shortBy(), item.unit());
			row.put("short_by", shortBy.value());
			row.put("unit", shortBy.unit());
			rows.add(row);
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("today", today);
		result.put("items", rows);
		result.put("returned", rows.size());
		result.put("total_items", all.size());
		result.put("truncated", all.size() > rows.size());
		local.fillIfMissing();
		return result;
	}

	// ---- list_meal_kinds -------------------------------------------------

	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public Map<String, Object> listMealKinds() {
		List<Map<String, Object>> kinds = new ArrayList<>();
		for (MealKindView kind : mealKindService.list()) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", kind.id());
			row.put("name", kind.name());
			putIfPresent(row, "usual_ready_by", time(kind.defaultReadyTime()));
			row.put("is_event", kind.isEvent());
			if (kind.needsOccasion()) {
				row.put("needs_occasion", true);
			}
			kinds.add(row);
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("meal_kinds", kinds);
		return result;
	}

	// ---- search_recipes --------------------------------------------------

	/**
	 * The temple's own active recipes whose name contains {@code query}: the recipes a meal can be
	 * planned from. Not the shared library, whose recipes cannot go on a meal until the temple has
	 * taken a copy.
	 */
	@PreAuthorize("hasAuthority('MANAGE_RECIPES')")
	public Map<String, Object> searchRecipes(String query) {
		List<RecipeSummary> all = recipeService.list(null, null, query, false, false);
		// The picker's own Ekadashi filter, read the same way, rather than a second definition of
		// "Ekadashi-friendly" here. Only for the rows actually returned.
		Set<UUID> ekadashiOk = all.isEmpty() ? Set.of()
				: recipeService.list(null, null, query, false, true).stream()
						.map(RecipeSummary::id).collect(Collectors.toSet());
		Names dishNames = names.forCurrentAgent();
		List<Map<String, Object>> rows = new ArrayList<>();
		for (RecipeSummary recipe : all) {
			if (rows.size() >= MAX_RECIPES) {
				break;
			}
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", recipe.id());
			row.put("name", recipe.name());
			putDishLocal(row, recipe.name(), dishNames);
			putIfPresent(row, "category", recipe.categoryName());
			row.put("ekadashi_ok", ekadashiOk.contains(recipe.id()));
			if (recipe.perHeadQty() != null) {
				Shown perHead = Shown.of(recipe.perHeadQty(), recipe.perHeadUnit());
				row.put("per_person", perHead.value());
				row.put("per_person_unit", perHead.unit());
			}
			rows.add(row);
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("recipes", rows);
		result.put("returned", rows.size());
		result.put("total_matching", all.size());
		result.put("truncated", all.size() > rows.size());
		return result;
	}

	// ---- Helpers ---------------------------------------------------------

	/**
	 * A dish's name in the conversation's language, where the temple's glossary has one. Never
	 * {@code name_pending}: see the class comment. The {@link Names} passed here is never asked to
	 * fill, so a dish without a glossary entry does not start a stock-name fill that would not name it.
	 */
	private static void putDishLocal(Map<String, Object> row, String dishName, Names dishNames) {
		putDishLocal(row, dishName, dishNames, "name_local");
	}

	private static void putDishLocal(Map<String, Object> row, String dishName, Names dishNames, String key) {
		String local = dishNames.local(dishName);
		if (local != null) {
			row.put(key, local);
		}
	}

	private static void putIfPresent(Map<String, Object> row, String key, Object value) {
		if (value != null) {
			row.put(key, value);
		}
	}

	private static String weekday(LocalDate date) {
		return date == null ? null : date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
	}

	/** "07:30": the seconds are never set and only cost the model a read. */
	private static String time(LocalTime time) {
		return time == null ? null : "%02d:%02d".formatted(time.getHour(), time.getMinute());
	}

	static LocalDate parse(String value, String argument) {
		try {
			return LocalDate.parse(value.trim());
		} catch (DateTimeParseException e) {
			throw new McpServerConfiguration.ToolRefusal(
					"%s must be a date written YYYY-MM-DD, for example 2026-10-11. Work out the date from today's date and call again."
							.formatted(argument));
		}
	}

	/**
	 * The range a call reads: {@code from} to {@code to} inclusive, one day when {@code to} is absent,
	 * starting today when {@code from} is absent, and cut to {@value #MAX_DAYS} days.
	 */
	record Range(LocalDate from, LocalDate to, LocalDate askedTo) {

		static Range of(String from, String to, LocalDate today) {
			LocalDate start = from == null ? today : parse(from, "from");
			LocalDate end = to == null ? start : parse(to, "to");
			if (end.isBefore(start)) {
				throw new McpServerConfiguration.ToolRefusal(
						"to is before from. Pass the earlier date as from and the later one as to.");
			}
			LocalDate last = start.plusDays(MAX_DAYS - 1);
			return new Range(start, end.isAfter(last) ? last : end, end);
		}

		boolean shortened() {
			return !to.equals(askedTo);
		}

		/** The first fields of every ranged answer. */
		Map<String, Object> head(LocalDate today) {
			Map<String, Object> head = new LinkedHashMap<>();
			head.put("today", today);
			head.put("today_weekday", weekday(today));
			head.put("from", from);
			head.put("to", to);
			if (shortened()) {
				head.put("range_shortened", true);
			}
			return head;
		}
	}

	/**
	 * A quantity in the unit it reads in, with the unit's label: 2792 gm is 2.792 Kg, as the shopping
	 * list shows it ({@link PurchaseOrderAction.Shown}), and at most three decimals, as the stock tools.
	 * A unit this application does not know (there should be none) is passed through as stored rather
	 * than failing the whole answer.
	 */
	record Shown(BigDecimal value, String unit, Unit as) {

		static Shown of(BigDecimal value, String storedUnit) {
			Unit unit = unit(storedUnit);
			if (unit == null || value == null) {
				return new Shown(round(value), storedUnit, null);
			}
			PurchaseOrderAction.Shown shown = PurchaseOrderAction.Shown.of(value, unit);
			BigDecimal rounded = round(shown.value());
			return new Shown(rounded, shown.unit().label(rounded), shown.unit());
		}

		/** {@code value}, stored in {@code storedUnit}, converted to the unit {@code like} reads in. */
		static BigDecimal in(BigDecimal value, String storedUnit, Shown like) {
			Unit from = unit(storedUnit);
			if (value == null || from == null || like.as() == null || from.family() != like.as().family()) {
				return round(value);
			}
			BigDecimal base = value.multiply(BigDecimal.valueOf(from.baseFactor()));
			return round(base.divide(BigDecimal.valueOf(like.as().baseFactor()), 6, RoundingMode.HALF_UP));
		}

		private static Unit unit(String stored) {
			if (stored == null) {
				return null;
			}
			try {
				return Unit.valueOf(stored);
			} catch (IllegalArgumentException e) {
				return null;
			}
		}

		private static BigDecimal round(BigDecimal value) {
			if (value == null) {
				return null;
			}
			BigDecimal rounded = value.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros();
			return rounded.scale() < 0 ? rounded.setScale(0) : rounded;
		}
	}
}
