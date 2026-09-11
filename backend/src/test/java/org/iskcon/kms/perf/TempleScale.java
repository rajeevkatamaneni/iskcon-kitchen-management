package org.iskcon.kms.perf;

/**
 * How big a temple {@link TempleScaleFixture} should build.
 *
 * <p><strong>Every default here is a projection from what staging actually holds, not a round
 * number.</strong> That distinction matters more than it looks: a fixture built from tidy powers of
 * ten measures a shape the product does not have, and the two reads T-139 exists to measure are
 * sensitive to shape rather than to size alone. {@code earliestDemandByIngredient()} joins meal
 * plans to recipe ingredients, so what governs it is *how many dishes a day* times *how many
 * ingredients a dish names*, and both of those are small, fixed numbers a temple does not change.
 * {@code onHandBaseByIngredient()} aggregates the whole of {@code stock_movements}, so what governs
 * it is *how many years the temple has been running*.
 *
 * <p>Where the numbers come from:
 *
 * <ul>
 *   <li><strong>150 ingredients, 116 inventory items, 26 recipes</strong> — the live counts on
 *       staging on 2026-09-10, quoted in the T-139 brief. Not every ingredient is stocked, which is
 *       why the second number is smaller than the first, and the gap is kept.
 *   <li><strong>9 dishes a day</strong> — three meals a day (breakfast, lunch, dinner), three dishes
 *       in each. The temple's own recipe master classifies by course, and a served meal is a rice, a
 *       dal and a sabji rather than one dish.
 *   <li><strong>an event every 14 days</strong> — named events on top of the three daily meals, which
 *       is how this product models occasions since E4-S15.
 *   <li><strong>8 ingredients per recipe</strong> — the median line count in {@code RM 2019_v2.xlsx}.
 *   <li><strong>80 stock movements a day</strong> — the growing table, and the one number worth
 *       arguing about. 9 dishes cooking 8 ingredients each is 72 consumption rows; a delivery lands
 *       every few days and covers a dozen or so lines; and somebody records a spoilage or a count
 *       correction most days. 80 is those three added up and rounded down.
 *   <li><strong>1825 days of history</strong> — five years. Nobody has ever run this list against
 *       five years of ledger, which is the whole point of the task: the aggregate has no date bound,
 *       so its cost is the temple's entire operating life.
 *   <li><strong>45 days ahead</strong> — the ordering horizon is 14 days, stretched to 30 to reach a
 *       festival ({@code CommittedStockService.BASE_HORIZON_DAYS} and
 *       {@code FESTIVAL_LOOKAHEAD_DAYS}). 45 fills that window and leaves plans beyond it, so the
 *       window is genuinely a filter rather than a no-op.
 * </ul>
 *
 * <p>Every field is overridable from the environment, so the size can be turned up without editing
 * anything. Environment rather than a system property on purpose: {@code backend/build.gradle.kts}
 * does not forward {@code -D} flags to the test worker, and this task may not edit that file — but a
 * Gradle test worker inherits the environment of the process that launched it. So
 * {@code KMS_PERF_HISTORY_DAYS=3650 ./gradlew test --tests '*ShoppingListPerformanceIT*'} works with
 * no build change at all.
 */
record TempleScale(
		int ingredients,
		int inventoryItems,
		int recipes,
		int ingredientsPerRecipe,
		int dishesPerDay,
		int eventEveryDays,
		int historyDays,
		int forwardDays,
		int movementsPerDay,
		int vendors,
		int purchaseOrders,
		int linesPerPurchaseOrder) {

	/** The projection described in this class's own documentation. */
	static TempleScale staging() {
		return new TempleScale(150, 116, 26, 8, 9, 14, 1825, 45, 80, 8, 260, 6);
	}

	/**
	 * The staging projection with any field the environment names replaced.
	 *
	 * <p>{@code KMS_PERF_YEARS} is offered beside {@code KMS_PERF_HISTORY_DAYS} because years is how
	 * the question is actually asked — "what does this look like after ten years?" — and multiplying
	 * by 365 in a shell is the sort of thing that gets done wrong once and then quoted.
	 */
	static TempleScale fromEnvironment() {
		TempleScale base = staging();
		int historyDays = base.historyDays();
		Integer years = intFromEnvironment("KMS_PERF_YEARS");
		if (years != null) {
			historyDays = years * 365;
		}
		return new TempleScale(
				orDefault("KMS_PERF_INGREDIENTS", base.ingredients()),
				orDefault("KMS_PERF_INVENTORY_ITEMS", base.inventoryItems()),
				orDefault("KMS_PERF_RECIPES", base.recipes()),
				orDefault("KMS_PERF_INGREDIENTS_PER_RECIPE", base.ingredientsPerRecipe()),
				orDefault("KMS_PERF_DISHES_PER_DAY", base.dishesPerDay()),
				orDefault("KMS_PERF_EVENT_EVERY_DAYS", base.eventEveryDays()),
				orDefault("KMS_PERF_HISTORY_DAYS", historyDays),
				orDefault("KMS_PERF_FORWARD_DAYS", base.forwardDays()),
				orDefault("KMS_PERF_MOVEMENTS_PER_DAY", base.movementsPerDay()),
				orDefault("KMS_PERF_VENDORS", base.vendors()),
				orDefault("KMS_PERF_PURCHASE_ORDERS", base.purchaseOrders()),
				orDefault("KMS_PERF_LINES_PER_PO", base.linesPerPurchaseOrder()));
	}

	TempleScale withHistoryDays(int days) {
		return new TempleScale(ingredients, inventoryItems, recipes, ingredientsPerRecipe, dishesPerDay,
				eventEveryDays, days, forwardDays, movementsPerDay, vendors, purchaseOrders,
				linesPerPurchaseOrder);
	}

	// --- what the generator must produce, stated as arithmetic ------------------------------------
	//
	// These are the expectations TempleScaleFixture.verify() checks the database against. They are
	// deliberately written here, beside the parameters, rather than inside the generator: a generator
	// that computed its own expectations from whatever it happened to insert would agree with itself
	// however wrong it was.

	int expectedRecipeIngredients() {
		return recipes * ingredientsPerRecipe;
	}

	int expectedEventsPast() {
		return historyDays / eventEveryDays;
	}

	int expectedEventsFuture() {
		return forwardDays / eventEveryDays;
	}

	int expectedCookedPlans() {
		return historyDays * dishesPerDay + expectedEventsPast();
	}

	int expectedPlannedPlans() {
		return forwardDays * dishesPerDay + expectedEventsFuture();
	}

	int expectedMealPlans() {
		return expectedCookedPlans() + expectedPlannedPlans();
	}

	/** One opening receipt per ingredient, then the daily churn. */
	int expectedStockMovements() {
		return ingredients + historyDays * movementsPerDay;
	}

	int expectedVendorSupplies() {
		return ingredients;
	}

	int expectedPurchaseOrderLines() {
		return purchaseOrders * linesPerPurchaseOrder;
	}

	double historyYears() {
		return historyDays / 365.0;
	}

	private static int orDefault(String name, int fallback) {
		Integer value = intFromEnvironment(name);
		return value == null ? fallback : value;
	}

	private static Integer intFromEnvironment(String name) {
		String raw = System.getenv(name);
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return Integer.parseInt(raw.trim());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(
					name + " must be a whole number, but was \"" + raw + "\"", e);
		}
	}
}
