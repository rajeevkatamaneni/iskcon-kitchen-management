package org.iskcon.kms.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.meal.MealFixture;
import org.iskcon.kms.notification.NotificationRecipient;
import org.iskcon.kms.notification.NotificationService;
import org.iskcon.kms.notification.NotificationTemplate;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.testsupport.StubTokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * An ingredient the temple never buys never reads <em>Low</em> (T-430).
 *
 * <p>V153 let a temple say "we never buy this" and the shopping list honoured it. The low-stock
 * judgement did not, so the seeded temple's water sat at <strong>minus 1,358 litres</strong> in the
 * low-stock list, in the dashboard's count and in the nightly digest at once. <em>Low</em> is an
 * instruction to act and the action is to buy; a temple cannot buy water. That figure is not a
 * shortage, it is a ledger artefact of cooking with something nobody stocks, and it grows for as
 * long as the temple keeps cooking.
 *
 * <p><strong>The fixture travels the path water actually travels.</strong> Water here has
 * <em>no reorder threshold at all</em> and is negative only because the plan has promised it — the
 * first disjunct of the Low rule, not the threshold comparison. A fix that guarded only the
 * threshold clause would leave every assertion in this class failing, which is the point of
 * building it this way; {@code InventoryStockIT.overCommittedWithoutAThresholdIsStillLow} pins the
 * same path from the other side. Rice sits beside it, low by the ordinary threshold route and
 * unmarked, so what each test shows is the difference between two rows rather than an empty screen.
 *
 * <p><strong>Absence is read off the response</strong>, in {@link #lowStockIds}, never matched with
 * a JSONPath filter — a filter that finds nothing and a filter that finds a row saying something
 * else both come back empty, which is the one failure a test about an absence must not have.
 */
@AutoConfigureMockMvc
class NotBoughtLowStockIT extends AbstractIntegrationTest {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
	private static final ObjectMapper JSON = new ObjectMapper();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private LowStockDigestRunner runner;

	/** Mocked for the reason {@code LowStockDigestIT} mocks it: this is about who is told what. */
	@MockBean
	private NotificationService notificationService;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID staffId;
	private UUID rice;
	private UUID water;
	private UUID waterItem;
	private final LocalDate day = LocalDate.now(IST).plusDays(2);

	@BeforeEach
	void setUp() {
		TenantContext.clear();
		admin = new JdbcTemplate(adminDataSource());
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		staffId = admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-staff-a', 'Staff A', 'staff-a@example.com', '+919876500081',
					'KITCHEN_STAFF', 'ACTIVE')
				RETURNING id
				""", UUID.class, tenant);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-admin-a', 'Admin A', 'admin-a@example.com', '+919876500082',
					'TEMPLE_ADMIN', 'ACTIVE')
				""", tenant);

		rice = ingredient("Rice", "KG");
		water = ingredient("Water", "L");

		// Rice: low the ordinary way. 15 Kg on the shelf, 10 Kg promised to the meal below, a
		// reorder level of 10 — so 5 Kg available against a level of 10. Positive, and still Low.
		item(rice, "10");
		receipt(rice, "15", "KG");

		// Water: NO reorder threshold and nothing ever received, which is water's real situation in
		// every temple. It goes negative purely because the plan promises it, and that is the
		// disjunct this task exists for.
		waterItem = item(water, null);

		UUID category = admin.queryForObject(
				"INSERT INTO recipe_categories (tenant_id, name) VALUES (?, 'Rice') RETURNING id",
				UUID.class, tenant);
		UUID khichdi = admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
				VALUES (?, 'Khichdi', ?, 100, 'KG') RETURNING id
				""", UUID.class, tenant, category);
		line(khichdi, rice, "5", "KG", 0);
		line(khichdi, water, "30", "L", 1);
		UUID meal = MealFixture.meal(admin, tenant, day, "Lunch", LocalTime.NOON);
		MealFixture.dish(admin, tenant, meal, khichdi, BigDecimal.valueOf(200), "PLANNED", staffId);

		signIn("uid-admin-a");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM low_stock_digest_runs");
		admin.execute("DELETE FROM audit_events");
		MealFixture.deleteAll(admin);
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM recipe_ingredients");
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	/**
	 * The whole of it in one pair of reads: before the mark, water is on the low-stock list at minus
	 * 60 litres with no threshold set; after it, there is no water line at all — and rice, low for an
	 * ordinary reason, is untouched.
	 */
	@Test
	@DisplayName("a marked ingredient is gone from the low-stock endpoint, even negative with no threshold")
	void itLeavesTheLowStockEndpoint() throws Exception {
		// The control: water is Low for the reason the real temple's water is Low.
		assertThat(lowStockIds()).containsExactlyInAnyOrder(rice, water);
		JsonNode before = row(body("/api/v1/inventory/items/low-stock"), "Water");
		assertThat(before.get("reorderThreshold").isNull())
				.as("no reorder level was ever set — it is Low through the negative disjunct alone")
				.isTrue();
		assertThat(new BigDecimal(before.get("available").asText())).isEqualByComparingTo("-60");
		assertThat(before.get("belowThreshold").asBoolean()).isTrue();

		markNotBought(water);

		assertThat(lowStockIds())
				.as("KMS never tells anybody to go and buy something the temple has said it never buys")
				.containsExactly(rice);
	}

	/**
	 * The dashboard counts the flag itself rather than calling the endpoint, so it is asserted
	 * separately — and {@code itemsTracked} is asserted <strong>not</strong> to move, which is the
	 * deliberate asymmetry. The numerator asks "how many things should somebody act on"; the
	 * denominator asks "is this temple tracking anything at all", and water genuinely is tracked.
	 */
	@Test
	@DisplayName("the dashboard's below-par count drops it; its tracked count still counts it")
	void theDashboardCountDropsItButTheTrackedCountKeepsIt() throws Exception {
		mvc.perform(authed(get("/api/v1/today")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.itemsBelowThreshold").value(2))
				.andExpect(jsonPath("$.itemsTracked").value(2));

		markNotBought(water);

		mvc.perform(authed(get("/api/v1/today")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.itemsBelowThreshold").value(1))
				.andExpect(jsonPath("$.itemsTracked")
						.value(2));
	}

	/**
	 * The nightly digest. Its wording is a registered WhatsApp template and is not touched — what
	 * changes is which items reach {@code items} and {@code count}.
	 */
	@Test
	@DisplayName("the nightly digest stops naming it, and counts one thing fewer")
	void theDigestStopsNamingIt() throws Exception {
		markNotBought(water);
		TenantContext.clear();

		assertThat(runner.sweep()).isEqualTo(1);

		Map<String, Object> params = digestParams();
		assertThat(params.get("count")).as("water is not one of the things to go and buy").isEqualTo(1);
		assertThat((String) params.get("items"))
				.as("read as a whole string, so a name appearing anywhere in it fails")
				.contains("Rice")
				.doesNotContain("Water");
	}

	/**
	 * Acceptance 3's second half, proved rather than assumed: a temple whose only low item is water
	 * gets no digest at all. Nothing new does this — it falls out of the story's own
	 * suppressed-when-empty rule, because the list reaching that rule is now empty. The right
	 * silence: silence means there is nothing to go and buy, and there is nothing to go and buy.
	 */
	@Test
	@DisplayName("a temple whose only low item is water gets no digest at all")
	void aTempleWhoseOnlyLowItemIsWaterGetsNoDigest() throws Exception {
		// Take rice out of the reckoning, so water is the only thing left that is Low.
		receipt(rice, "100", "KG");
		assertThat(lowStockIds()).containsExactly(water);

		markNotBought(water);
		assertThat(lowStockIds()).isEmpty();
		TenantContext.clear();

		assertThat(runner.sweep()).as("nothing is low, so no temple is digested").isZero();
		verify(notificationService, never())
				.notify(any(), eq(NotificationTemplate.LOW_STOCK_DIGEST), any(), any());
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM low_stock_digest_runs WHERE tenant_id = ?", Integer.class, tenant))
				.as("suppressed means not sent, not sent-empty — no run is claimed either")
				.isZero();
	}

	/**
	 * Only the <em>judgement</em> changes. The consumable is still listed, still carries its real
	 * figures, and still has a stock detail page — V153 promised that water "still goes into the pot,
	 * it still draws down stock, it is still costed", and a {@code WHERE NOT is_not_bought} in the
	 * shared select would have 404'd this page.
	 *
	 * <p>The field asserted here is the same {@code belowThreshold} the inventory screen filters and
	 * counts on in the browser, which is how acceptance 4 is proved against the payload: the screen
	 * has no other source for its badge or its tally.
	 */
	@Test
	@DisplayName("it is still tracked, still committed, still has a detail page — it just is not Low")
	void itIsStillTrackedItJustIsNotLow() throws Exception {
		markNotBought(water);

		// On the stock list: present, with its real minus-60 figure, and no longer badged Low.
		String list = body("/api/v1/inventory/items");
		assertThat(JSON.readTree(list).size()).as("nothing is filtered out of the stock list").isEqualTo(2);

		JsonNode waterRow = row(list, "Water");
		assertThat(new BigDecimal(waterRow.get("committed").asText()))
				.as("the plan still promises 60 L of it")
				.isEqualByComparingTo("60");
		assertThat(new BigDecimal(waterRow.get("available").asText()))
				.as("and the figure is still told truthfully — only the badge changed")
				.isEqualByComparingTo("-60");
		assertThat(waterRow.get("belowThreshold").asBoolean())
				.as("this is the field the inventory screen filters and tallies on in the browser")
				.isFalse();
		assertThat(row(list, "Rice").get("belowThreshold").asBoolean())
				.as("the unmarked control is still Low")
				.isTrue();

		// And its own page still opens, saying the same thing.
		String detail = mvc.perform(authed(get("/api/v1/inventory/items/{id}", waterItem)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.item.ingredientName").value("Water"))
				.andReturn().getResponse().getContentAsString();
		JsonNode page = JSON.readTree(detail).get("item");
		assertThat(new BigDecimal(page.get("available").asText())).isEqualByComparingTo("-60");
		assertThat(page.get("belowThreshold").asBoolean())
				.as("the detail page badges it from the same field, so it agrees with the list")
				.isFalse();
		// The meals that promised it are still listed against it — the plan is not rewritten.
		assertThat(JSON.readTree(detail).get("committed").size())
				.as("the dish that claims the water is still shown on its page")
				.isEqualTo(1);
	}

	/**
	 * The other disjunct, so the mark is shown to beat the whole rule and not just the negative case:
	 * a marked ingredient that is comfortably positive but under its reorder level is dropped too.
	 */
	@Test
	@DisplayName("a marked ingredient under its reorder level is dropped as well, not only a negative one")
	void theThresholdRouteIsDroppedToo() throws Exception {
		// Rice is the threshold case: 15 Kg in, 10 Kg promised, level 10 — 5 available, positive.
		assertThat(lowStockIds()).contains(rice);

		markNotBought(rice);

		assertThat(lowStockIds()).containsExactly(water);
	}

	/** The mirror of the shopping list's: nothing is stored, so the next read computes it back. */
	@Test
	@DisplayName("clearing the mark puts it back, recomputed rather than restored")
	void clearingTheMarkPutsItBack() throws Exception {
		markNotBought(water);
		assertThat(lowStockIds()).containsExactly(rice);

		setNotBought(water, false);

		assertThat(lowStockIds()).containsExactlyInAnyOrder(rice, water);
	}

	// ---------------------------------------------------------------------

	/** The ingredient ids on the low-stock list, read off the body rather than matched on a field. */
	private List<UUID> lowStockIds() throws Exception {
		String body = mvc.perform(authed(get("/api/v1/inventory/items/low-stock")))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		List<UUID> ids = new ArrayList<>();
		for (JsonNode row : JSON.readTree(body)) {
			ids.add(UUID.fromString(row.get("ingredientId").asText()));
		}
		return ids;
	}

	/** A GET as the signed-in person, returning the raw body for the helpers below to read. */
	private String body(String path) throws Exception {
		return mvc.perform(authed(get(path)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
	}

	/**
	 * One named row out of a stock payload, failing loudly when it is not there.
	 *
	 * <p>A JSONPath filter was tried first and is deliberately not used: {@code $[?(@.name=='Water')]}
	 * answers the same for a row that is missing and a row that is present saying something else, so
	 * every assertion built on one is weaker than it looks. Pulling the row out and asserting on its
	 * fields separates "absent" from "wrong", which is the whole discipline of this class.
	 */
	private JsonNode row(String payload, String ingredientName) throws Exception {
		for (JsonNode candidate : JSON.readTree(payload)) {
			if (ingredientName.equals(candidate.get("ingredientName").asText())) {
				return candidate;
			}
		}
		throw new AssertionError("no row for " + ingredientName + " in: " + payload);
	}

	/** The parameters the digest was actually built with, for whoever it reached first. */
	@SuppressWarnings("unchecked")
	private Map<String, Object> digestParams() {
		ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
		verify(notificationService, org.mockito.Mockito.atLeastOnce()).notify(
				any(NotificationRecipient.class), eq(NotificationTemplate.LOW_STOCK_DIGEST),
				captor.capture(), any());
		return captor.getValue();
	}

	private void markNotBought(UUID ingredientId) throws Exception {
		setNotBought(ingredientId, true);
	}

	/** Through the real endpoint, behind {@code MANAGE_BUYING_POLICY} — a Temple Admin only. */
	private void setNotBought(UUID ingredientId, boolean notBought) throws Exception {
		signIn("uid-admin-a");
		mvc.perform(authed(patch("/api/v1/ingredients/{id}/not-bought", ingredientId))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"notBought\":%s}".formatted(notBought)))
				.andExpect(status().isNoContent());
	}

	private UUID ingredient(String name, String unit) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Grains', ?) RETURNING id
				""", UUID.class, tenant, name, unit);
	}

	private UUID item(UUID ingredientId, String threshold) {
		return admin.queryForObject("""
				INSERT INTO inventory_items (tenant_id, ingredient_id, reorder_threshold)
				VALUES (?, ?, ?::numeric) RETURNING id
				""", UUID.class, tenant, ingredientId, threshold);
	}

	private void receipt(UUID ingredientId, String quantity, String unit) {
		admin.update("""
				INSERT INTO stock_movements (tenant_id, ingredient_id, batch_id, quantity, unit,
						movement_type, actor_user_id)
				VALUES (?, ?, ?, ?::numeric, ?, 'PO_RECEIPT', ?)
				""", tenant, ingredientId, UUID.randomUUID(), quantity, unit, staffId);
	}

	private void line(UUID recipeId, UUID ingredientId, String quantity, String unit, int order) {
		admin.update("""
				INSERT INTO recipe_ingredients (tenant_id, recipe_id, ingredient_id, quantity, unit, line_order)
				VALUES (?, ?, ?, ?::numeric, ?, ?)
				""", tenant, recipeId, ingredientId, quantity, unit, order);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}
}
