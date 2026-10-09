package org.iskcon.kms.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.assistant.token.AgentPrincipal;
import org.iskcon.kms.calendar.CalendarService;
import org.iskcon.kms.meal.MealFixture;
import org.iskcon.kms.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The assistant's read-only meal tools (E10-MEAL-READ), called through the handlers the server
 * registers, with the temple put on the thread where the agent-token filter puts it.
 *
 * <p>Built on {@link McpStockFixture}'s two temples, each given a Lunch on the same day, so a tool
 * that leaked across temples would show the other temple's dish. Temple A's Lunch is a Sambar that
 * needs 5 Kg of Toor Dal against the 2 Kg temple A holds, so it is short by 3 Kg; temple B's is a
 * Rice Bath its 3 Kg of rice does not cover either, and must never appear in temple A's answers.
 * Everything is read through the application's unprivileged connection, so RLS is what keeps the
 * temples apart, not the tool.
 */
class MealToolsIT extends AbstractIntegrationTest {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	/** A computed Ekadashi for the fixture's Bengaluru coordinates, as {@code EkadashiFlaggingIT} uses. */
	private static final String EKADASHI = "2025-01-10";
	private static final String ORDINARY = "2025-01-13";

	private static final String SAMBAR_KN = "ಸಾಂಬಾರ್";

	@Autowired
	private InventoryTools inventoryTools;

	@Autowired
	private ConversationTools conversationTools;

	@Autowired
	private PurchaseOrderTools purchaseOrderTools;

	@Autowired
	private MealTools mealTools;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private CalendarService calendarService;

	private JdbcTemplate admin;
	private McpStockFixture fixture;

	/** Two days ahead: inside the buying window, so sufficiency makes a claim about it. */
	private final LocalDate lunchDay = LocalDate.now(IST).plusDays(2);

	@BeforeEach
	void setUp() {
		TenantContext.clear();
		SecurityContextHolder.clearContext();
		admin = new JdbcTemplate(adminDataSource());
		fixture = new McpStockFixture(admin);

		UUID sambar = recipe(fixture.templeA, "Sambar", "Toor Dal", "5");
		UUID lunchA = MealFixture.meal(admin, fixture.templeA, lunchDay, "Lunch", LocalTime.of(12, 30));
		MealFixture.headCount(admin, lunchA, 80, 15, 5);
		MealFixture.dish(admin, fixture.templeA, lunchA, sambar, new BigDecimal("100"), fixture.userA);
		fixture.name(fixture.templeA, "kn", "Sambar", SAMBAR_KN);

		UUID riceBath = recipe(fixture.templeB, "Rice Bath", "Basmati Rice", "10");
		UUID lunchB = MealFixture.meal(admin, fixture.templeB, lunchDay, "Lunch", LocalTime.of(12, 0));
		MealFixture.dish(admin, fixture.templeB, lunchB, riceBath, new BigDecimal("100"), fixture.userB);
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		SecurityContextHolder.clearContext();
		MealFixture.deleteAll(admin);
		admin.execute("DELETE FROM calendar_overrides");
		admin.execute("DELETE FROM calendar_days");
		admin.execute("DELETE FROM calendar_precompute_state");
		admin.execute("DELETE FROM recipe_ingredients");
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		admin.execute("DELETE FROM audit_events");
		McpStockFixture.tearDown(admin);
	}

	// ---- Each temple sees only its own meals -----------------------------

	@Test
	@DisplayName("get_meals returns only the calling temple's meal, with head counts, amounts and the dish's stored name")
	void getMealsIsTenantScoped() throws Exception {
		JsonNode a = ok(call(captureAs(fixture.templeA, "MANAGE_MEAL_PLANS"), McpServerConfiguration.GET_MEALS,
				Map.of("from", lunchDay.toString())));

		assertThat(a.get("today").asText()).isEqualTo(LocalDate.now(IST).toString());
		assertThat(a.get("meals")).hasSize(1);
		JsonNode lunch = a.get("meals").get(0);
		assertThat(lunch.get("meal_kind").asText()).isEqualTo("Lunch");
		assertThat(lunch.get("ready_by").asText()).isEqualTo("12:30");
		assertThat(lunch.get("adults").asInt()).isEqualTo(80);
		assertThat(lunch.get("children").asInt()).isEqualTo(15);
		assertThat(lunch.get("seniors").asInt()).isEqualTo(5);
		assertThat(lunch.get("recorded").asBoolean()).isFalse();
		assertThat(lunch.get("dishes")).hasSize(1);
		JsonNode dish = lunch.get("dishes").get(0);
		assertThat(dish.get("name").asText()).isEqualTo("Sambar");
		assertThat(dish.get("name_local").asText()).isEqualTo(SAMBAR_KN);
		assertThat(dish.get("amount").decimalValue()).isEqualByComparingTo("100");
		assertThat(dish.get("unit").asText()).isEqualTo("Kg");
		assertThat(dish.has("name_pending")).as("a dish is never marked pending: nothing would fill it").isFalse();

		JsonNode b = ok(call(captureAs(fixture.templeB, "MANAGE_MEAL_PLANS"), McpServerConfiguration.GET_MEALS,
				Map.of("from", lunchDay.toString())));
		assertThat(b.get("meals")).hasSize(1);
		assertThat(b.get("meals").get(0).get("dishes").get(0).get("name").asText()).isEqualTo("Rice Bath");
		assertThat(b.get("meals").get(0).get("dishes").get(0).has("name_local")).isFalse();
	}

	@Test
	@DisplayName("check_meal_sufficiency reports temple A's dish short of its own dal, and nothing of temple B's")
	void sufficiencyIsTenantScoped() throws Exception {
		JsonNode a = ok(call(captureAs(fixture.templeA, "MANAGE_MEAL_PLANS"),
				McpServerConfiguration.CHECK_MEAL_SUFFICIENCY, Map.of("from", lunchDay.toString())));

		assertThat(a.get("dishes")).hasSize(1);
		JsonNode dish = a.get("dishes").get(0);
		assertThat(dish.get("dish").asText()).isEqualTo("Sambar");
		assertThat(dish.get("dish_local").asText()).isEqualTo(SAMBAR_KN);
		assertThat(dish.get("status").asText()).isEqualTo("short");
		assertThat(a.get("dishes_short").asInt()).isEqualTo(1);
		JsonNode dal = dish.get("short_of").get(0);
		assertThat(dal.get("name").asText()).isEqualTo("Toor Dal");
		assertThat(dal.get("name_local").asText()).isEqualTo(McpStockFixture.DAL_KN_A);
		assertThat(dal.get("needed").decimalValue()).isEqualByComparingTo("5");
		assertThat(dal.get("available").decimalValue()).isEqualByComparingTo("2");
		assertThat(dal.get("short_by").decimalValue()).isEqualByComparingTo("3");
		assertThat(dal.get("unit").asText()).isEqualTo("Kg");
		assertThat(dish.get("order_by")).as("a short dish says when to order by").isNotNull();

		JsonNode shortfall = ok(call(captureAs(fixture.templeA, "MANAGE_MEAL_PLANS"),
				McpServerConfiguration.GET_SHORTFALL, Map.of()));
		assertThat(shortfall.get("items")).extracting(n -> n.get("name").asText()).containsExactly("Toor Dal");
		assertThat(shortfall.get("items").get(0).get("short_by").decimalValue()).isEqualByComparingTo("3");

		JsonNode b = ok(call(captureAs(fixture.templeB, "MANAGE_MEAL_PLANS"),
				McpServerConfiguration.CHECK_MEAL_SUFFICIENCY, Map.of("from", lunchDay.toString())));
		assertThat(b.get("dishes")).extracting(n -> n.get("dish").asText()).containsExactly("Rice Bath");
	}

	// ---- Ekadashi ----------------------------------------------------------

	@Test
	@DisplayName("get_day_context flags an Ekadashi and not an ordinary day, and lists the day's meals")
	void dayContextFlagsEkadashi() throws Exception {
		// The calendar is computed ahead per temple, as provisioning does, not on each read.
		TenantContext.set(fixture.templeA);
		try {
			calendarService.precomputeForCurrentTenant(LocalDate.of(2025, 1, 1), 40);
		} finally {
			TenantContext.clear();
		}
		McpTransportContext asA = captureAs(fixture.templeA, "MANAGE_MEAL_PLANS");

		JsonNode ekadashi = ok(call(asA, McpServerConfiguration.GET_DAY_CONTEXT, Map.of("date", EKADASHI)));
		assertThat(ekadashi.get("is_ekadashi").asBoolean()).isTrue();
		assertThat(ekadashi.get("weekday").asText()).isEqualTo("Friday");

		JsonNode ordinary = ok(call(asA, McpServerConfiguration.GET_DAY_CONTEXT, Map.of("date", ORDINARY)));
		assertThat(ordinary.get("is_ekadashi").asBoolean()).isFalse();
		assertThat(ordinary.has("ekadashi_name")).isFalse();

		JsonNode lunch = ok(call(asA, McpServerConfiguration.GET_DAY_CONTEXT, Map.of("date", lunchDay.toString())));
		assertThat(lunch.get("meals")).extracting(n -> n.get("meal_kind").asText()).containsExactly("Lunch");

		JsonNode today = ok(call(asA, McpServerConfiguration.GET_DAY_CONTEXT, Map.of()));
		assertThat(today.get("date").asText()).as("no date is today, in the temple's zone")
				.isEqualTo(LocalDate.now(IST).toString());
	}

	// ---- Caps ---------------------------------------------------------------

	@Test
	@DisplayName("a range longer than 14 days is cut and says so; meals stop at 60 and recipes at 10")
	void resultsAreCapped() throws Exception {
		McpTransportContext asA = captureAs(fixture.templeA, "MANAGE_MEAL_PLANS", "MANAGE_RECIPES");
		LocalDate from = LocalDate.now(IST);

		JsonNode month = ok(call(asA, McpServerConfiguration.GET_MEALS,
				Map.of("from", from.toString(), "to", from.plusDays(30).toString())));
		assertThat(month.get("to").asText()).isEqualTo(from.plusDays(MealTools.MAX_DAYS - 1).toString());
		assertThat(month.get("range_shortened").asBoolean()).isTrue();

		for (int i = 1; i <= MealTools.MAX_MEALS; i++) {
			MealFixture.meal(admin, fixture.templeA, from.plusDays(i % 5), "Event", "Event %02d".formatted(i),
					LocalTime.of(18, 0));
		}
		JsonNode many = ok(call(asA, McpServerConfiguration.GET_MEALS,
				Map.of("from", from.toString(), "to", from.plusDays(6).toString())));
		assertThat(many.get("meals")).hasSize(MealTools.MAX_MEALS);
		assertThat(many.get("total_meals").asInt()).isEqualTo(MealTools.MAX_MEALS + 1);
		assertThat(many.get("truncated").asBoolean()).isTrue();

		for (int i = 1; i <= 12; i++) {
			recipe(fixture.templeA, "Idli %02d".formatted(i), "Ghee", "1");
		}
		JsonNode idli = ok(call(asA, McpServerConfiguration.SEARCH_RECIPES, Map.of("query", "idli")));
		assertThat(idli.get("recipes")).hasSize(MealTools.MAX_RECIPES);
		assertThat(idli.get("total_matching").asInt()).isEqualTo(12);
		assertThat(idli.get("truncated").asBoolean()).isTrue();
		assertThat(idli.get("recipes").get(0).get("id").asText()).isNotBlank();
	}

	@Test
	@DisplayName("search_recipes and list_meal_kinds read only the calling temple's rows")
	void lookupsAreTenantScoped() throws Exception {
		JsonNode b = ok(call(captureAs(fixture.templeB, "MANAGE_MEAL_PLANS", "MANAGE_RECIPES"),
				McpServerConfiguration.SEARCH_RECIPES, Map.of("query", "a")));
		assertThat(b.get("recipes")).extracting(n -> n.get("name").asText()).containsExactly("Rice Bath");

		JsonNode kinds = ok(call(captureAs(fixture.templeB, "MANAGE_MEAL_PLANS"),
				McpServerConfiguration.LIST_MEAL_KINDS, Map.of()));
		UUID ownLunch = MealFixture.kindId(admin, fixture.templeB, "Lunch");
		assertThat(kinds.get("meal_kinds")).extracting(n -> n.get("id").asText()).contains(ownLunch.toString())
				.doesNotContain(MealFixture.kindId(admin, fixture.templeA, "Lunch").toString());
	}

	// ---- The person's own permission ---------------------------------------

	@Test
	@DisplayName("a person without the meal planner or the recipes screen is refused in words")
	void permissionIsEnforced() {
		actAs(fixture.templeA, "agent:read", "VIEW_OWN_SHIFTS");
		assertThatThrownBy(() -> mealTools.getMeals(null, null)).isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> mealTools.checkMealSufficiency(null, null)).isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> mealTools.getShortfall()).isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> mealTools.getDayContext(null)).isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> mealTools.listMealKinds()).isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> mealTools.searchRecipes("idli")).isInstanceOf(AccessDeniedException.class);
		SecurityContextHolder.clearContext();
		TenantContext.clear();

		CallToolResult meals = call(captureAs(fixture.templeA, "agent:read"), McpServerConfiguration.GET_MEALS, Map.of());
		assertThat(meals.isError()).isTrue();
		assertThat(text(meals)).isEqualTo(McpServerConfiguration.NOT_ALLOWED_MEALS_MESSAGE);

		// The meal planner's permission does not open the recipes screen.
		CallToolResult recipes = call(captureAs(fixture.templeA, "agent:read", "MANAGE_MEAL_PLANS"),
				McpServerConfiguration.SEARCH_RECIPES, Map.of("query", "sambar"));
		assertThat(recipes.isError()).isTrue();
		assertThat(text(recipes)).isEqualTo(McpServerConfiguration.NOT_ALLOWED_RECIPES_MESSAGE);
	}

	@Test
	@DisplayName("a date the model wrote wrongly, or backwards, is refused with what to do instead")
	void badDatesAreRefusedInWords() {
		McpTransportContext asA = captureAs(fixture.templeA, "MANAGE_MEAL_PLANS");

		CallToolResult tomorrow = call(asA, McpServerConfiguration.GET_MEALS, Map.of("from", "tomorrow"));
		assertThat(tomorrow.isError()).isTrue();
		assertThat(text(tomorrow)).contains("YYYY-MM-DD");

		CallToolResult backwards = call(asA, McpServerConfiguration.CHECK_MEAL_SUFFICIENCY,
				Map.of("from", "2026-10-10", "to", "2026-10-01"));
		assertThat(backwards.isError()).isTrue();
		assertThat(text(backwards)).contains("before from");
	}

	// ---------------------------------------------------------------------

	/** A recipe yielding 100 Kg that needs {@code quantity} Kg of one of the fixture's ingredients. */
	private UUID recipe(UUID tenant, String name, String ingredient, String quantity) {
		UUID category = admin.query("SELECT id FROM recipe_categories WHERE tenant_id = ? AND name = 'Mains'",
				(rs, n) -> rs.getObject("id", UUID.class), tenant).stream().findFirst()
				.orElseGet(() -> admin.queryForObject("""
						INSERT INTO recipe_categories (tenant_id, name) VALUES (?, 'Mains') RETURNING id
						""", UUID.class, tenant));
		UUID recipe = admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
				VALUES (?, ?, ?, 100, 'KG') RETURNING id
				""", UUID.class, tenant, name, category);
		UUID ingredientId = admin.queryForObject(
				"SELECT id FROM ingredients WHERE tenant_id = ? AND name = ?", UUID.class, tenant, ingredient);
		String unit = admin.queryForObject(
				"SELECT canonical_unit FROM ingredients WHERE id = ?", String.class, ingredientId);
		admin.update("""
				INSERT INTO recipe_ingredients (tenant_id, recipe_id, ingredient_id, quantity, unit, line_order)
				VALUES (?, ?, ?, ?::numeric, ?, 0)
				""", tenant, recipe, ingredientId, quantity, unit);
		return recipe;
	}

	/** The assistant for the temple's admin, in a Kannada conversation, as {@link InventoryToolsIT} does. */
	private void actAs(UUID tenant, String... authorities) {
		UUID user = tenant.equals(fixture.templeA) ? fixture.userA : fixture.userB;
		UUID conversation = fixture.conversation(tenant, user, "kn");
		TenantContext.set(tenant);
		AgentPrincipal agent = new AgentPrincipal(user, tenant, conversation, java.util.Set.of("agent:read"));
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
				agent, null, AuthorityUtils.createAuthorityList(authorities)));
	}

	private McpTransportContext captureAs(UUID tenant, String... authorities) {
		actAs(tenant, authorities);
		McpTransportContext captured = AgentCallScope.captureCurrent();
		TenantContext.clear();
		SecurityContextHolder.clearContext();
		return captured;
	}

	private CallToolResult call(McpTransportContext context, String toolName, Map<String, Object> arguments) {
		List<SyncToolSpecification> specs = McpServerConfiguration.toolSpecifications(
				inventoryTools, conversationTools, purchaseOrderTools, mealTools, objectMapper);
		SyncToolSpecification spec = specs.stream()
				.filter(s -> s.tool().name().equals(toolName))
				.findFirst()
				.orElseThrow();
		return spec.callHandler().apply(context, new CallToolRequest(toolName, arguments));
	}

	private JsonNode ok(CallToolResult result) throws Exception {
		assertThat(result.isError()).as(text(result)).isFalse();
		return objectMapper.readTree(text(result));
	}

	private static String text(CallToolResult result) {
		return ((TextContent) result.content().get(0)).text();
	}
}
