package org.iskcon.kms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.attachment.AttachmentController;
import org.iskcon.kms.ingredient.IngredientController;
import org.iskcon.kms.ingredient.MarketRateController;
import org.iskcon.kms.ingredient.merge.IngredientMergeController;
import org.iskcon.kms.inventory.InventoryItemController;
import org.iskcon.kms.invoice.InvoicePaymentController;
import org.iskcon.kms.invoice.VendorInvoiceController;
import org.iskcon.kms.purchaseorder.PurchaseOrderController;
import org.iskcon.kms.receiving.DeliveriesController;
import org.iskcon.kms.receiving.GoodsReturnController;
import org.iskcon.kms.receiving.ReceivingController;
import org.iskcon.kms.recipe.RecipeController;
import org.iskcon.kms.shoppinglist.ShoppingListController;
import org.iskcon.kms.testsupport.StubTokenVerifier;
import org.iskcon.kms.user.User;
import org.iskcon.kms.vendor.VendorController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The permission refusal comes before anything is read from the request (T-301).
 *
 * <p>The verifiers' finding: a Volunteer posting a malformed body to a procurement endpoint was
 * answered 400 KMS-400001 with the form's field errors, and only a well-formed body reached the 403.
 * Spring reads and validates the body while building the controller method's arguments, and method
 * security only runs once they exist. {@link PermissionFirstInterceptor} asks the same
 * {@code @PreAuthorize} question before that happens.
 *
 * <p>Every write endpoint in the procurement build is listed in {@link #endpoints()}, each with a
 * request that is wrong in the way that used to win: an empty or incomplete body that fails
 * {@code @Valid}, JSON that does not parse where the body is not validated, a missing multipart
 * parameter, or an address whose id is not an id where the endpoint has no body at all. For each one:
 *
 * <ul>
 *   <li>a Volunteer gets 403 KMS-400021, and not one row in any table changes;
 *   <li>a role that holds the permission still gets the 400 (or, for an unreadable id in the address,
 *       the 404 the product already gives for that) — the useful answer for a person who may be
 *       there is untouched.
 * </ul>
 *
 * <p>{@link #everyProcurementWriteEndpointIsListed} keeps the list honest: it reads the application's
 * own request mappings and fails if one of these controllers gains a write endpoint the list above
 * does not name, so a new endpoint cannot quietly go unchecked.
 *
 * <p>Deliberately no {@code @Import} or nested configuration: this class uses the real controllers
 * and shares the context every other integration class uses.
 */
class PermissionBeforeValidationIT extends AbstractIntegrationTest {

	private static final String VOLUNTEER_TOKEN = "t301-volunteer";

	/** An id that does not parse, for endpoints whose only input is the address. */
	private static final String NOT_AN_ID = "not-an-id";

	/** JSON that ends half way through. Spring cannot even start to read it. */
	private static final String UNREADABLE = "{\"lines\": [";

	@Autowired
	private TestRestTemplate rest;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping mappings;

	@Autowired
	private ObjectMapper json;

	@LocalServerPort
	private int port;

	private JdbcTemplate admin;
	private UUID tenantId;

	/** One request, wrong in one way. {@code path} is the mapping's own pattern. */
	record Probe(
			HttpMethod method, String path, Permission permission, Body body, HttpStatus permittedStatus) {

		@Override
		public String toString() {
			return method + " " + path + " (" + body + ")";
		}
	}

	enum Body {
		/** {@code {}}, which fails {@code @Valid} on a required field. */
		EMPTY_OBJECT,
		/** The purchase order the verifier sent: {@code {"lines": []}}. */
		NO_LINES,
		/** JSON that does not parse, for a body that is optional or not validated. */
		UNREADABLE_JSON,
		/** A multipart form with neither the file nor, where asked for, its kind. */
		MULTIPART_WITHOUT_FILE,
		/** No body; the first id in the address is not an id. */
		NONE_BAD_ID
	}

	static Stream<Probe> endpoints() {
		HttpMethod get = HttpMethod.GET;
		HttpMethod post = HttpMethod.POST;
		HttpMethod put = HttpMethod.PUT;
		HttpMethod patch = HttpMethod.PATCH;
		HttpMethod delete = HttpMethod.DELETE;
		HttpStatus bad = HttpStatus.BAD_REQUEST;
		HttpStatus missing = HttpStatus.NOT_FOUND;

		return Stream.of(
				// Purchase orders
				new Probe(post, "/api/v1/purchase-orders", Permission.MANAGE_PURCHASE_ORDERS, Body.NO_LINES, bad),
				new Probe(put, "/api/v1/purchase-orders/{id}", Permission.MANAGE_PURCHASE_ORDERS, Body.EMPTY_OBJECT, bad),
				new Probe(post, "/api/v1/purchase-orders/{id}/send", Permission.MANAGE_PURCHASE_ORDERS, Body.UNREADABLE_JSON, bad),
				new Probe(post, "/api/v1/purchase-orders/{id}/close", Permission.MANAGE_PURCHASE_ORDERS, Body.EMPTY_OBJECT, bad),
				new Probe(post, "/api/v1/purchase-orders/{id}/cancel", Permission.MANAGE_PURCHASE_ORDERS, Body.EMPTY_OBJECT, bad),
				new Probe(post, "/api/v1/purchase-orders/{id}/arrivals", Permission.MANAGE_PURCHASE_ORDERS, Body.EMPTY_OBJECT, bad),
				new Probe(post, "/api/v1/purchase-orders/{id}/whatsapp", Permission.MANAGE_PURCHASE_ORDERS, Body.NONE_BAD_ID, missing),

				// Shopping list
				new Probe(post, "/api/v1/shopping-list", Permission.MANAGE_PURCHASE_ORDERS, Body.EMPTY_OBJECT, bad),
				new Probe(patch, "/api/v1/shopping-list/{ingredientId}", Permission.MANAGE_PURCHASE_ORDERS, Body.UNREADABLE_JSON, bad),

				// Deliveries, receipts and returns
				new Probe(post, "/api/v1/deliveries", Permission.RECEIVE_DELIVERIES, Body.EMPTY_OBJECT, bad),
				new Probe(post, "/api/v1/purchase-orders/{poId}/receipts", Permission.RECEIVE_DELIVERIES, Body.EMPTY_OBJECT, bad),
				new Probe(post, "/api/v1/goods-receipts/{receiptId}/returns", Permission.MANAGE_INVENTORY, Body.EMPTY_OBJECT, bad),

				// Invoices and payments
				new Probe(post, "/api/v1/vendor-invoices", Permission.MANAGE_PURCHASE_ORDERS, Body.EMPTY_OBJECT, bad),
				new Probe(post, "/api/v1/vendor-invoices/{id}/void", Permission.MANAGE_VENDOR_PAYMENTS, Body.EMPTY_OBJECT, bad),
				new Probe(post, "/api/v1/vendor-invoices/{id}/credit", Permission.MANAGE_VENDOR_PAYMENTS, Body.EMPTY_OBJECT, bad),
				new Probe(post, "/api/v1/vendor-invoices/{id}/payments", Permission.MANAGE_VENDOR_PAYMENTS, Body.EMPTY_OBJECT, bad),
				new Probe(post, "/api/v1/vendor-invoices/{invoiceId}/payments/{paymentId}/reverse", Permission.MANAGE_VENDOR_PAYMENTS, Body.EMPTY_OBJECT, bad),

				// Uploads
				new Probe(post, "/api/v1/vendor-invoices/bill-uploads", Permission.MANAGE_PURCHASE_ORDERS, Body.MULTIPART_WITHOUT_FILE, bad),
				new Probe(post, "/api/v1/vendor-invoices/payment-uploads", Permission.MANAGE_VENDOR_PAYMENTS, Body.MULTIPART_WITHOUT_FILE, bad),

				// Vendors: the record, what they supply, their pack sizes and prices
				new Probe(post, "/api/v1/vendors", Permission.MANAGE_VENDORS, Body.EMPTY_OBJECT, bad),
				new Probe(put, "/api/v1/vendors/{id}", Permission.MANAGE_VENDORS, Body.EMPTY_OBJECT, bad),
				new Probe(post, "/api/v1/vendors/{id}/deactivate", Permission.MANAGE_VENDORS, Body.UNREADABLE_JSON, bad),
				new Probe(post, "/api/v1/vendors/{id}/reactivate", Permission.MANAGE_VENDORS, Body.UNREADABLE_JSON, bad),
				new Probe(put, "/api/v1/vendors/{id}/supplies", Permission.MANAGE_VENDORS, Body.EMPTY_OBJECT, bad),
				new Probe(post, "/api/v1/vendors/{id}/supplies/bulk", Permission.MANAGE_VENDORS, Body.EMPTY_OBJECT, bad),
				new Probe(delete, "/api/v1/vendors/{id}/supplies/{ingredientId}", Permission.MANAGE_VENDORS, Body.NONE_BAD_ID, missing),

				// Ingredients: the record, its two standing flags, its pack sizes, market rate, and
				// merging duplicates
				new Probe(post, "/api/v1/ingredients", Permission.MANAGE_RECIPES, Body.EMPTY_OBJECT, bad),
				new Probe(put, "/api/v1/ingredients/{id}", Permission.MANAGE_RECIPES, Body.EMPTY_OBJECT, bad),
				new Probe(patch, "/api/v1/ingredients/{id}/ekadashi-flag", Permission.MANAGE_DIETARY_POLICY, Body.UNREADABLE_JSON, bad),
				// T-402. Unreadable JSON rather than {} for the same reason the Ekadashi row above
				// uses it: SetNotBoughtRequest carries one primitive and no constraint, so an empty
				// object is a perfectly valid request that would answer 204 and prove nothing about
				// the order the permission is asked in. Half-finished JSON cannot be read at all, so
				// the 400 it earns is the answer the Volunteer must NOT be given.
				new Probe(patch, "/api/v1/ingredients/{id}/not-bought", Permission.MANAGE_BUYING_POLICY, Body.UNREADABLE_JSON, bad),
				new Probe(delete, "/api/v1/ingredients/{id}", Permission.MANAGE_RECIPES, Body.NONE_BAD_ID, missing),
				new Probe(post, "/api/v1/ingredients/{id}/pack-sizes", Permission.MANAGE_RECIPES, Body.EMPTY_OBJECT, bad),
				new Probe(delete, "/api/v1/ingredients/{id}/pack-sizes/{packSizeId}", Permission.MANAGE_RECIPES, Body.NONE_BAD_ID, missing),
				new Probe(put, "/api/v1/ingredients/{id}/market-rate", Permission.MANAGE_INVENTORY, Body.UNREADABLE_JSON, bad),
				new Probe(post, "/api/v1/ingredients/merges/preview", Permission.MERGE_INGREDIENTS, Body.UNREADABLE_JSON, bad),
				new Probe(post, "/api/v1/ingredients/merges", Permission.MERGE_INGREDIENTS, Body.UNREADABLE_JSON, bad),

				// Copying a library recipe, and the close matches asked about first
				new Probe(post, "/api/v1/recipes/import/{masterRecipeId}", Permission.MANAGE_RECIPES, Body.UNREADABLE_JSON, bad),
				new Probe(get, "/api/v1/recipes/import/{masterRecipeId}/close-matches", Permission.MANAGE_RECIPES, Body.NONE_BAD_ID, missing),

				// Inventory items, created with their opening count
				new Probe(post, "/api/v1/inventory/items", Permission.MANAGE_INVENTORY, Body.EMPTY_OBJECT, bad),
				new Probe(put, "/api/v1/inventory/items/{id}", Permission.MANAGE_INVENTORY, Body.UNREADABLE_JSON, bad),
				new Probe(delete, "/api/v1/inventory/items/{id}", Permission.MANAGE_INVENTORY, Body.NONE_BAD_ID, missing),
				new Probe(post, "/api/v1/inventory/items/{id}/adjustments", Permission.MANAGE_INVENTORY, Body.EMPTY_OBJECT, bad));
	}

	/**
	 * Repeating an event and cancelling a series (T-307), added to the same two checks because they
	 * take a body a Volunteer should never get as far as having read. Not part of
	 * {@link #everyProcurementWriteEndpointIsListed}, which is about the procurement controllers: the
	 * rest of {@code MealController} predates this class, and T-307 was asked to cover what it built.
	 *
	 * <p>The repeat's body is required and validated, so {@code {}} fails {@code @Valid}. The cancel's
	 * body is optional and an empty one is a real cancel, so it is sent unreadable instead. The two
	 * reads take only the address, so theirs is an id that is not an id.
	 */
	static Stream<Probe> mealSeriesEndpoints() {
		HttpMethod get = HttpMethod.GET;
		HttpMethod post = HttpMethod.POST;
		return Stream.of(
				new Probe(post, "/api/v1/meals/{id}/repeat", Permission.MANAGE_MEAL_PLANS, Body.EMPTY_OBJECT, HttpStatus.BAD_REQUEST),
				new Probe(post, "/api/v1/meals/{id}/cancel", Permission.MANAGE_MEAL_PLANS, Body.UNREADABLE_JSON, HttpStatus.BAD_REQUEST),
				new Probe(get, "/api/v1/meals/{id}/repeat-preview", Permission.MANAGE_MEAL_PLANS, Body.NONE_BAD_ID, HttpStatus.NOT_FOUND),
				new Probe(get, "/api/v1/meals/{id}/later-in-series", Permission.MANAGE_MEAL_PLANS, Body.NONE_BAD_ID, HttpStatus.NOT_FOUND));
	}

	/**
	 * Arranging the temple's own menu and putting the standard one back (T-420), added here because
	 * the body is the reason: it is a blob — every group and every destination the temple has — and
	 * reading it is the expensive part of the request. Somebody who may not arrange the menu should
	 * never get that far.
	 *
	 * <p>Not part of {@link #everyProcurementWriteEndpointIsListed}, which is about the procurement
	 * controllers; the rest of {@code SettingsController} predates this class.
	 *
	 * <p>The reset takes no body and no id, and for a role that holds the permission it is a real
	 * reset: 204, whether or not the temple had arranged anything. So its permitted answer is the
	 * only success on this list, and that is exactly why it is worth probing — the refusal has to
	 * arrive before the endpoint does the thing.
	 */
	static Stream<Probe> menuLayoutEndpoints() {
		return Stream.of(
				new Probe(HttpMethod.PUT, "/api/v1/settings/menu-layout",
						Permission.MANAGE_TEMPLE_SETTINGS, Body.EMPTY_OBJECT, HttpStatus.BAD_REQUEST),
				new Probe(HttpMethod.DELETE, "/api/v1/settings/menu-layout",
						Permission.MANAGE_TEMPLE_SETTINGS, Body.NONE_BAD_ID, HttpStatus.NO_CONTENT));
	}

	/** Every probe the three parameterised sources give the two checks below. */
	static Stream<Probe> everyProbe() {
		return Stream.concat(Stream.concat(endpoints(), mealSeriesEndpoints()), menuLayoutEndpoints());
	}

	/**
	 * The controllers whose write endpoints the procurement build added or changed, and — for the two
	 * that are mostly about something else — the part of them it touched.
	 */
	private static final Set<Class<?>> WHOLE_CONTROLLERS = Set.of(
			PurchaseOrderController.class,
			ShoppingListController.class,
			DeliveriesController.class,
			ReceivingController.class,
			GoodsReturnController.class,
			VendorInvoiceController.class,
			InvoicePaymentController.class,
			AttachmentController.class,
			VendorController.class,
			IngredientController.class,
			MarketRateController.class,
			IngredientMergeController.class,
			InventoryItemController.class);

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		tenantId = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('t301-permission-first', 'Sri Sri Radha Krishna Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);

		for (User.Role role : List.of(
				User.Role.VOLUNTEER, User.Role.KITCHEN_STAFF, User.Role.KITCHEN_MANAGER, User.Role.TEMPLE_ADMIN)) {
			String uid = "uid-t301-" + role.name().toLowerCase();
			String email = role.name().toLowerCase() + "@t301.example.com";
			String phone = "+9198765301" + (10 + role.ordinal());
			admin.update("""
					INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
					VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')
					""", tenantId, uid, "Test " + role, email, phone, role.name());
			stubVerifier.accept(tokenFor(role), new TokenVerifier.VerifiedSubject(uid, email, phone));
		}
	}

	@AfterEach
	void tearDown() {
		admin.update("DELETE FROM users WHERE tenant_id = ?", tenantId);
		admin.update("DELETE FROM tenants WHERE id = ?", tenantId);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("everyProbe")
	@DisplayName("a Volunteer with a malformed request is refused 403 KMS-400021, and nothing is written")
	void volunteerIsRefusedBeforeTheBodyIsRead(Probe probe) throws Exception {
		assertThat(RolePermissions.has(User.Role.VOLUNTEER, probe.permission()))
				.as("this test needs a role without %s; the Volunteer now holds it", probe.permission())
				.isFalse();

		Map<String, Long> before = rowCounts();
		ResponseEntity<String> response = send(probe, VOLUNTEER_TOKEN);

		assertThat(response.getStatusCode())
				.as("%s as a Volunteer: %s", probe, response.getBody())
				.isEqualTo(HttpStatus.FORBIDDEN);
		JsonNode body = json.readTree(response.getBody());
		assertThat(body.path("code").asText()).isEqualTo("KMS-400021");
		assertThat(body.path("fieldErrors").isMissingNode() || body.path("fieldErrors").isEmpty())
				.as("a refusal must not describe the form: %s", response.getBody())
				.isTrue();
		assertThat(response.getBody()).doesNotContain(probe.permission().name());

		assertThat(rowCounts()).as("rows written by a refused request").isEqualTo(before);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("everyProbe")
	@DisplayName("a role that holds the permission still gets the request's own error")
	void permittedRoleStillGetsTheFieldErrors(Probe probe) throws Exception {
		User.Role permitted = Stream.of(
						User.Role.TEMPLE_ADMIN, User.Role.KITCHEN_MANAGER, User.Role.KITCHEN_STAFF)
				.filter(role -> RolePermissions.has(role, probe.permission()))
				.findFirst()
				.orElseThrow(() -> new AssertionError("no temple role holds " + probe.permission()));

		Map<String, Long> before = rowCounts();
		ResponseEntity<String> response = send(probe, tokenFor(permitted));

		assertThat(response.getStatusCode())
				.as("%s as %s: %s", probe, permitted, response.getBody())
				.isEqualTo(probe.permittedStatus());
		assertThat(codeOf(response)).isNotEqualTo("KMS-400021");

		assertThat(rowCounts()).as("rows written by a malformed request").isEqualTo(before);
	}

	@Test
	@DisplayName("every write endpoint of the procurement controllers is in the list above")
	void everyProcurementWriteEndpointIsListed() {
		Set<String> listed = new TreeSet<>();
		endpoints().forEach(probe -> listed.add(probe.method() + " " + probe.path()));

		Set<String> mapped = new TreeSet<>();
		for (Map.Entry<RequestMappingInfo, org.springframework.web.method.HandlerMethod> entry
				: mappings.getHandlerMethods().entrySet()) {
			Class<?> controller = entry.getValue().getBeanType();
			for (String pattern : entry.getKey().getPatternValues()) {
				boolean procurementPartOfRecipes = controller == RecipeController.class
						&& pattern.startsWith("/api/v1/recipes/import/");
				if (!WHOLE_CONTROLLERS.contains(controller) && !procurementPartOfRecipes) {
					continue;
				}
				for (RequestMethod method : entry.getKey().getMethodsCondition().getMethods()) {
					if (method != RequestMethod.GET || procurementPartOfRecipes) {
						mapped.add(method.name() + " " + pattern);
					}
				}
			}
		}

		assertThat(mapped).as("mapped write endpoints").isNotEmpty();
		assertThat(listed).as("the probe list against the application's own mappings").isEqualTo(mapped);
	}

	// ---------------------------------------------------------------------

	/**
	 * The {@code KMS-nnnnnn} on a response, or nothing where the response has no body.
	 *
	 * <p>Every probe on this list used to answer with an error body, so the code could be read
	 * straight off it. The menu-layout reset does not: for a role that holds the permission it
	 * succeeds, and a 204 carries nothing. Absent is the right reading — a response with no body is
	 * certainly not the refusal this check is watching for — and it beats leaving the one endpoint
	 * whose permitted answer is a success off the list.
	 */
	private String codeOf(ResponseEntity<String> response) throws Exception {
		String body = response.getBody();
		return body == null || body.isBlank() ? "" : json.readTree(body).path("code").asText();
	}

	private static String tokenFor(User.Role role) {
		return role == User.Role.VOLUNTEER ? VOLUNTEER_TOKEN : "t301-" + role.name().toLowerCase();
	}

	private ResponseEntity<String> send(Probe probe, String token) {
		String path = probe.path();
		if (probe.body() == Body.NONE_BAD_ID) {
			path = path.replaceFirst("\\{[^}]+}", NOT_AN_ID);
		}
		path = path.replaceAll("\\{[^}]+}", UUID.randomUUID().toString());

		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token);
		HttpEntity<?> entity = switch (probe.body()) {
			case EMPTY_OBJECT -> jsonEntity("{}", headers);
			case NO_LINES -> jsonEntity("{\"lines\": []}", headers);
			case UNREADABLE_JSON -> jsonEntity(UNREADABLE, headers);
			case MULTIPART_WITHOUT_FILE -> {
				headers.setContentType(MediaType.MULTIPART_FORM_DATA);
				MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
				form.add("note", "no file attached");
				yield new HttpEntity<>(form, headers);
			}
			case NONE_BAD_ID -> new HttpEntity<>(headers);
		};
		return rest.exchange("http://localhost:" + port + path, probe.method(), entity, String.class);
	}

	private static HttpEntity<String> jsonEntity(String body, HttpHeaders headers) {
		headers.setContentType(MediaType.APPLICATION_JSON);
		return new HttpEntity<>(body, headers);
	}

	/**
	 * The number of rows in every table, read as the superuser so that no policy hides one. "Nothing
	 * written" is checked against the whole schema rather than the tables each endpoint is expected to
	 * touch, because the question is whether anything at all happened.
	 */
	private Map<String, Long> rowCounts() {
		Map<String, Long> counts = new LinkedHashMap<>();
		for (String table : admin.queryForList("""
				SELECT table_name FROM information_schema.tables
				WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
				ORDER BY table_name
				""", String.class)) {
			counts.put(table, admin.queryForObject("SELECT count(*) FROM \"" + table + "\"", Long.class));
		}
		return counts;
	}
}
