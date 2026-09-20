package org.iskcon.kms.ingredient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.auth.TokenVerifier;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.inventory.MovementType;
import org.iskcon.kms.inventory.RecordMovement;
import org.iskcon.kms.inventory.StockMovementService;
import org.iskcon.kms.notification.NotificationService;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.testsupport.StubTokenVerifier;
import org.iskcon.kms.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * A counted thing cannot be a fraction, at every door a person or the API can enter one (T-423).
 *
 * <p>Seeding staging produced 7.2 LPG cylinders, 3.6 brooms, 2.4 mops and a return of 1.5 aprons,
 * and the application recorded every one of them without complaint; an apron finished sitting at
 * 88.5 in stock. The unit was known to be {@code PIECES} throughout. What nothing knew is that a
 * piece does not divide, and no column could have said so — every quantity column is
 * {@code NUMERIC(_, 3)}.
 *
 * <p>This is {@link UnitFamilyRefusalIT} for the second rule, held to the same bar, because the two
 * are the same shape: one rule, many doors, a specific code, and a field error that names the thing
 * rather than the form. Each door here is asked the same five things.
 *
 * <ol>
 *   <li>It refuses with <strong>{@code KMS-400191}</strong>, asserted by code. A bare status check
 *       passes just as happily against the wrong code, and the number is what somebody quotes off
 *       an old screenshot a year later.
 *   <li>The refusal <strong>names the thing and offers the whole numbers either side</strong>, so
 *       the next step is a figure rather than an instruction.
 *   <li>A form with one good line and one fractional line <strong>writes nothing</strong>.
 *   <li>A whole number in a counted unit is accepted, and <strong>a fractional Kg or litre figure
 *       is still accepted</strong>. That is the most important negative assertion in the file: the
 *       rule is keyed on {@link Unit.Family#COUNT}, and a leak into mass or volume would refuse half
 *       a kilo of rice, which is most of what a temple actually buys.
 *   <li>{@code 1.000} and {@code 2.0} are whole numbers. {@link java.math.BigDecimal#scale()} is not
 *       the question — a figure that has been through JDBC is scaled to its column and a genuine one
 *       apron arrives as {@code 1.000} — so the value is what is tested, at both scales.
 * </ol>
 *
 * <p>Two further things this file has to prove, which no single door can:
 *
 * <p><strong>The ledger's own last line of defence refuses too</strong>, reached directly rather
 * than through an endpoint, because an endpoint written next month may forget. And it refuses only
 * for the movement kinds whose figure a person typed — a cooking draw and an issue draw are worked
 * out by the application and are legitimately fractional against a counted ingredient.
 *
 * <p><strong>The rows already written still work.</strong> There is no migration and there was never
 * going to be one: the fractional rows on staging record what was entered, and that is honest. So
 * one is inserted here exactly as staging holds it and the application is made to go on reading it,
 * summing it and adding to it.
 */
@AutoConfigureMockMvc
class PartOfACountedThingIT extends AbstractIntegrationTest {

	/** Everything procurement, inventory and the catalogue. */
	private static final String ADMIN_TOKEN = "Bearer admin-token";

	/** A gift and a kitchen's request, as UnitFamilyRefusalIT drives them. */
	private static final String COOK_TOKEN = "Bearer cook-token";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private StockMovementService movements;

	@Autowired
	private UserRepository users;

	// A gift from a named donor queues a thank-you. None of these gifts has one, but the base class
	// keeps Quartz out of the context and this keeps the donation path off the scheduler entirely.
	@MockBean
	private NotificationService notificationService;

	private JdbcTemplate admin;
	private UUID temple;
	private UUID adminUser;
	private UUID cook;

	/** Counted one by one. Every refusal in this file is about this one. */
	private UUID apron;

	/** Measured, not counted. Every "the rule did not leak" assertion is about this one. */
	private UUID rice;

	private UUID apronItem;
	private UUID apronBatch;
	private UUID riceItem;
	private UUID riceBatch;
	private UUID vendor;
	private UUID kitchen;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		temple = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Sri Sri Radha Govinda Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);

		adminUser = insertUser("uid-admin", "Temple Admin", "TEMPLE_ADMIN", "+919876500091");
		cook = insertUser("uid-cook", "Bhakta Shyam", "KITCHEN_STAFF", "+919876500092");

		apron = insertIngredient("Apron", "PIECES", "Supplies");
		rice = insertIngredient("Rice", "KG", "Grains");

		apronItem = insertItem(apron);
		riceItem = insertItem(rice);

		// Opening stock, both whole and both written straight to the ledger, so nothing in this
		// fixture depends on the doors the tests are about.
		apronBatch = UUID.randomUUID();
		seedMovement(apron, apronBatch, "90", "PIECES");
		riceBatch = UUID.randomUUID();
		seedMovement(rice, riceBatch, "100", "KG");

		vendor = admin.queryForObject("""
				INSERT INTO vendors (tenant_id, name, phone) VALUES (?, 'Govind Wholesale', '+919812345678')
				RETURNING id
				""", UUID.class, temple);

		kitchen = admin.queryForObject("""
				INSERT INTO kitchens (tenant_id, name, uses_meal_planner, created_by)
				VALUES (?, 'Deity kitchen', false, ?) RETURNING id
				""", UUID.class, temple, cook);

		stubVerifier.accept("admin-token",
				new TokenVerifier.VerifiedSubject("uid-admin", "uid-admin@example.com", "+919876500091"));
		stubVerifier.accept("cook-token",
				new TokenVerifier.VerifiedSubject("uid-cook", "uid-cook@example.com", "+919876500092"));
	}

	@AfterEach
	void tearDown() {
		// Left set by actor(); a ThreadLocal outlives the test that set it, and the next class to run
		// on this thread would inherit a tenant it has never heard of.
		TenantContext.clear();
		admin.execute("DELETE FROM documents");
		admin.execute("DELETE FROM po_label_translations");
		admin.execute("DELETE FROM translation_glossary");
		admin.execute("DELETE FROM goods_returns");
		admin.execute("DELETE FROM goods_receipt_lines");
		admin.execute("DELETE FROM goods_receipts");
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM shopping_list_lines");
		admin.execute("DELETE FROM po_events");
		admin.execute("DELETE FROM purchase_order_lines");
		admin.execute("DELETE FROM purchase_orders");
		admin.execute("DELETE FROM po_sequence");
		admin.execute("DELETE FROM ingredient_pack_sizes");
		admin.execute("DELETE FROM vendor_supplies");
		admin.execute("DELETE FROM vendors");
		admin.execute("DELETE FROM donations");
		admin.execute("DELETE FROM ingredient_request_events");
		admin.execute("DELETE FROM ingredient_request_lines");
		admin.execute("DELETE FROM ingredient_request_dishes");
		admin.execute("DELETE FROM ingredient_requests");
		admin.execute("DELETE FROM ingredient_request_sequence");
		admin.execute("DELETE FROM kitchens");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ---- A stock count or correction -------------------------------------
	//
	// The door 88.5 came through. "Add to inventory" sends its opening count as exactly this
	// request, so the first count of a consumable and every later correction are one door.

	@Test
	@DisplayName("adjusting aprons by 2.5 is KMS-400191 naming Apron, offering 2 or 3, and the ledger is untouched")
	void adjustmentRefusesPartOfACountedThing() throws Exception {
		refusedNaming(mvc.perform(adjust(apronItem, apronBatch, "-2.5", "PIECES")),
				"Apron", "Apron is counted in whole pieces. Enter -3 or -2.");

		assertThat(movementCount()).as("only the two seeded receipts").isEqualTo(2);
		assertThat(onHandBase(apron)).isEqualByComparingTo("90");
	}

	@Test
	@DisplayName("adjusting aprons by a whole 2 is accepted")
	void adjustmentAcceptsAWholeCount() throws Exception {
		mvc.perform(adjust(apronItem, apronBatch, "-2", "PIECES")).andExpect(status().isCreated());

		assertThat(onHandBase(apron)).isEqualByComparingTo("88");
	}

	@Test
	@DisplayName("adjusting rice by half a kilo is still accepted — the rule must not leak out of COUNT")
	void adjustmentStillAcceptsAFractionalMass() throws Exception {
		mvc.perform(adjust(riceItem, riceBatch, "-0.5", "KG")).andExpect(status().isCreated());

		assertThat(onHandBase(rice)).as("half a kilo out of a hundred, in base grams")
				.isEqualByComparingTo("99500");
	}

	@Test
	@DisplayName("a fractional litre figure is accepted too, so the rule is about the family and not one unit")
	void adjustmentStillAcceptsAFractionalVolume() throws Exception {
		UUID ghee = insertIngredient("Ghee", "L", "Dairy");
		UUID gheeItem = insertItem(ghee);
		UUID gheeBatch = UUID.randomUUID();
		seedMovement(ghee, gheeBatch, "20", "L");

		mvc.perform(adjust(gheeItem, gheeBatch, "-1.75", "L")).andExpect(status().isCreated());

		assertThat(onHandBase(ghee)).as("18.25 L in base millilitres").isEqualByComparingTo("18250");
	}

	// ---- Scale is not the question; the value is -------------------------

	@Test
	@DisplayName("1.000 and 2.0 are whole numbers and are accepted at any scale")
	void trailingZeroesAreStillWholeNumbers() throws Exception {
		mvc.perform(adjust(apronItem, apronBatch, "-1.000", "PIECES")).andExpect(status().isCreated());
		mvc.perform(adjust(apronItem, apronBatch, "-2.0", "PIECES")).andExpect(status().isCreated());

		assertThat(onHandBase(apron)).as("three aprons written off in two adjustments")
				.isEqualByComparingTo("87");
	}

	@Test
	@DisplayName("7.200 is a fraction however it is written, and 0.5 is a fraction with no whole part")
	void trailingZeroesDoNotHideAFraction() throws Exception {
		refusedNaming(mvc.perform(adjust(apronItem, apronBatch, "-7.200", "PIECES")),
				"Apron", "Apron is counted in whole pieces. Enter -8 or -7.");
		refusedNaming(mvc.perform(adjust(apronItem, apronBatch, "0.5", "PIECES")),
				"Apron", "Apron is counted in whole pieces. Enter 0 or 1.");

		assertThat(movementCount()).as("neither was written").isEqualTo(2);
	}

	// ---- Ordering --------------------------------------------------------

	@Test
	@DisplayName("ordering 7.2 aprons is KMS-400191 naming Apron, and no order or line is written")
	void orderRefusesPartOfACountedThing() throws Exception {
		refusedNaming(mvc.perform(createOrder(orderLine(apron, "7.2", "PIECES"))),
				"Apron", "Apron is counted in whole pieces. Enter 7 or 8.");

		assertThat(count("purchase_orders")).isZero();
		assertThat(count("purchase_order_lines")).isZero();
	}

	@Test
	@DisplayName("a two-line order with one good line and one fractional line writes neither")
	void twoLineOrderIsRefusedWhole() throws Exception {
		refusedNaming(
				mvc.perform(createOrder(orderLine(rice, "25", "KG") + "," + orderLine(apron, "3.6", "PIECES"))),
				"Apron", "Apron is counted in whole pieces. Enter 3 or 4.");

		assertThat(count("purchase_orders")).as("not even the header").isZero();
		assertThat(count("purchase_order_lines")).as("the rice line was not written either").isZero();
	}

	@Test
	@DisplayName("an order for 12 aprons and 25.5 Kg of rice is accepted, both lines")
	void orderAcceptsAWholeCountBesideAFractionalMass() throws Exception {
		created(mvc.perform(createOrder(
				orderLine(apron, "12", "PIECES") + "," + orderLine(rice, "25.5", "KG"))));

		assertThat(count("purchase_order_lines")).isEqualTo(2);
		assertThat(admin.queryForObject(
				"SELECT quantity FROM purchase_order_lines WHERE ingredient_id = ?", BigDecimal.class, rice))
				.as("twenty-five and a half kilos, untouched").isEqualByComparingTo("25.5");
	}

	// ---- Receiving a delivery, and sending part of it back ---------------

	@Test
	@DisplayName("receiving 7.2 of the 12 aprons ordered is KMS-400191, and no receipt or movement is written")
	void receivingRefusesPartOfACountedThing() throws Exception {
		Ordered order = sentOrderForAprons();

		refusedNaming(mvc.perform(receive(order.poId(), order.lineId(), "7.2", "0")),
				"Apron", "Apron is counted in whole pieces. Enter 7 or 8.");

		assertThat(count("goods_receipts")).isZero();
		assertThat(movementCount()).as("only the two seeded receipts").isEqualTo(2);
	}

	@Test
	@DisplayName("rejecting half an apron off the lorry is refused too — both figures are counted at the gate")
	void receivingRefusesAFractionalRejection() throws Exception {
		Ordered order = sentOrderForAprons();

		refusedNaming(mvc.perform(receive(order.poId(), order.lineId(), "7", "0.5")),
				"Apron", "Apron is counted in whole pieces. Enter 0 or 1.");

		assertThat(count("goods_receipts")).isZero();
	}

	@Test
	@DisplayName("sending 1.5 aprons back to the vendor is KMS-400191, and the return is not recorded")
	void returnRefusesPartOfACountedThing() throws Exception {
		Ordered order = sentOrderForAprons();
		mvc.perform(receive(order.poId(), order.lineId(), "12", "0")).andExpect(status().isCreated());
		Received receipt = receipt();

		refusedNaming(mvc.perform(returnGoods(receipt.receiptId(), receipt.lineId(), "1.5")),
				"Apron", "Apron is counted in whole pieces. Enter 1 or 2.");

		assertThat(count("goods_returns")).isZero();
		assertThat(onHandBase(apron)).as("the twelve received are all still here").isEqualByComparingTo("102");
	}

	@Test
	@DisplayName("receiving twelve whole aprons and sending two back both work")
	void receivingAndReturningWholeCountsWork() throws Exception {
		Ordered order = sentOrderForAprons();
		mvc.perform(receive(order.poId(), order.lineId(), "12", "0")).andExpect(status().isCreated());
		Received receipt = receipt();

		mvc.perform(returnGoods(receipt.receiptId(), receipt.lineId(), "2")).andExpect(status().isCreated());

		assertThat(onHandBase(apron)).as("90 + 12 - 2").isEqualByComparingTo("100");
	}

	// ---- The shopping list -----------------------------------------------

	@Test
	@DisplayName("putting 3.6 brooms on the shopping list is KMS-400191 naming Broom, and no line is written")
	void shoppingListRefusesPartOfACountedThing() throws Exception {
		UUID broom = insertIngredient("Broom", "PIECES", "Supplies");

		refusedNaming(mvc.perform(addShoppingLine(broom, "3.6")),
				"Broom", "Broom is counted in whole pieces. Enter 3 or 4.");

		assertThat(count("shopping_list_lines")).isZero();
	}

	@Test
	@DisplayName("four brooms goes on the list, and so does 2.5 Kg of rice")
	void shoppingListAcceptsWholeCountsAndFractionalMass() throws Exception {
		UUID broom = insertIngredient("Broom", "PIECES", "Supplies");

		mvc.perform(addShoppingLine(broom, "4")).andExpect(status().isCreated());
		mvc.perform(addShoppingLine(rice, "2.5")).andExpect(status().isCreated());

		assertThat(count("shopping_list_lines")).isEqualTo(2);
	}

	// ---- A pack size -----------------------------------------------------

	@Test
	@DisplayName("a pack holding 30.5 aprons is KMS-400191, and no pack size is written")
	void packSizeRefusesPartOfACountedThing() throws Exception {
		refusedNaming(mvc.perform(addPackSize(apron, "Tray", "30.5", "PIECES")),
				"Apron", "Apron is counted in whole pieces. Enter 30 or 31.");

		assertThat(count("ingredient_pack_sizes")).isZero();
	}

	@Test
	@DisplayName("a pack holding 30 aprons is fine, and so is a 2.5 Kg bag of rice")
	void packSizeAcceptsWholeCountsAndFractionalMass() throws Exception {
		mvc.perform(addPackSize(apron, "Tray", "30", "PIECES")).andExpect(status().isCreated());
		mvc.perform(addPackSize(rice, "Bag", "2.5", "KG")).andExpect(status().isCreated());

		assertThat(count("ingredient_pack_sizes")).isEqualTo(2);
	}

	// ---- A gift of goods --------------------------------------------------

	@Test
	@DisplayName("a gift of 2.4 aprons is KMS-400191 naming Apron, and no donation or movement is written")
	void giftRefusesPartOfACountedThing() throws Exception {
		refusedNaming(mvc.perform(gift(giftLine(apron, "2.4", "PIECES"))),
				"Apron", "Apron is counted in whole pieces. Enter 2 or 3.");

		assertThat(count("donations")).isZero();
		assertThat(movementCount()).as("only the two seeded receipts").isEqualTo(2);
	}

	@Test
	@DisplayName("a two-line gift of good rice and 1.5 aprons is refused whole, naming Apron")
	void twoLineGiftIsRefusedWhole() throws Exception {
		refusedNaming(
				mvc.perform(gift(giftLine(rice, "5", "KG") + "," + giftLine(apron, "1.5", "PIECES"))),
				"Apron", "Apron is counted in whole pieces. Enter 1 or 2.");

		assertThat(count("donations")).isZero();
		assertThat(movementCount()).as("the rice line was not written either").isEqualTo(2);
		assertThat(onHandBase(rice)).isEqualByComparingTo("100000");
	}

	@Test
	@DisplayName("a gift of two aprons and 5.5 Kg of rice is taken into stock, both lines")
	void giftAcceptsAWholeCountBesideAFractionalMass() throws Exception {
		mvc.perform(gift(giftLine(apron, "2", "PIECES") + "," + giftLine(rice, "5.5", "KG")))
				.andExpect(status().isCreated());

		assertThat(onHandBase(apron)).isEqualByComparingTo("92");
		assertThat(onHandBase(rice)).isEqualByComparingTo("105500");
	}

	// ---- A kitchen's ingredient request ----------------------------------

	@Test
	@DisplayName("a request for 2.5 aprons is KMS-400191 naming Apron, and no request or line is written")
	void requestRefusesPartOfACountedThing() throws Exception {
		refusedNaming(mvc.perform(createRequest(requestLine(apron, "2.5", "PIECES"))),
				"Apron", "Apron is counted in whole pieces. Enter 2 or 3.");

		assertThat(count("ingredient_requests")).isZero();
		assertThat(count("ingredient_request_lines")).isZero();
	}

	@Test
	@DisplayName("a request for half an idli is refused on the dish, which has no catalogue row at all")
	void requestRefusesAFractionalDish() throws Exception {
		refusedNaming(
				mvc.perform(createRequest(requestLine(rice, "40", "KG"), "200.5", "PIECES")),
				"Idli", "Idli is counted in whole pieces. Enter 200 or 201.");

		assertThat(count("ingredient_requests")).isZero();
		assertThat(count("ingredient_request_dishes")).isZero();
	}

	@Test
	@DisplayName("a request for two aprons and 40 Kg of rice is accepted")
	void requestAcceptsAWholeCountBesideAFractionalMass() throws Exception {
		created(mvc.perform(createRequest(
				requestLine(apron, "2", "PIECES") + "," + requestLine(rice, "40.5", "KG"))));

		assertThat(count("ingredient_request_lines")).isEqualTo(2);
	}

	// ---- The ledger's own last line of defence ---------------------------

	@Test
	@DisplayName("the ledger refuses a fractional count reached directly, past every endpoint")
	void theLedgerRefusesPartOfACountedThing() {
		AuthenticatedUser actor = actor();

		// No endpoint reaches this — every one of them refuses earlier, which is the point. This is
		// what an endpoint written next month, forgetting to ask, runs into.
		assertThatThrownBy(() -> movements.record(actor, new RecordMovement(
				apron, null, UUID.randomUUID(), new BigDecimal("4.25"), Unit.PIECES,
				MovementType.PO_RECEIPT, null, null, null, null, null, null)))
				.isInstanceOf(ApplicationException.class)
				.extracting(e -> ((ApplicationException) e).errorCode())
				.isEqualTo(ErrorCode.PART_OF_A_COUNTED_THING);

		assertThat(movementCount()).as("nothing was appended").isEqualTo(2);
	}

	@Test
	@DisplayName("the ledger still takes a fractional mass, and a whole count, directly")
	void theLedgerStillTakesTheOrdinaryCases() {
		AuthenticatedUser actor = actor();

		assertThatCode(() -> movements.record(actor, new RecordMovement(
				rice, null, UUID.randomUUID(), new BigDecimal("4.25"), Unit.KG,
				MovementType.PO_RECEIPT, null, null, null, null, null, null)))
				.doesNotThrowAnyException();
		assertThatCode(() -> movements.record(actor, new RecordMovement(
				apron, null, UUID.randomUUID(), new BigDecimal("4.000"), Unit.PIECES,
				MovementType.PO_RECEIPT, null, null, null, null, null, null)))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("a cooking draw and an issue draw may still be fractional, because the application worked them out")
	void theLedgerLeavesTheApplicationsOwnArithmeticAlone() {
		AuthenticatedUser actor = actor();

		// This is the assertion that keeps the temple cooking. A recipe scaled to 12 L genuinely
		// needs 2.4 coconuts, and FEFO splits whatever is needed across whatever the lots hold — so
		// handing over five whole aprons out of a lot holding 2.5 posts a 2.5 movement. A blanket
		// check in the ledger would refuse both, and would make the fractional rows already on
		// staging impossible to draw down, which is the one thing the application must still be able
		// to do with them.
		assertThatCode(() -> movements.record(actor, new RecordMovement(
				apron, null, apronBatch, new BigDecimal("-2.4"), Unit.PIECES,
				MovementType.CONSUMPTION, null, null, null, null, null, null)))
				.doesNotThrowAnyException();
		assertThatCode(() -> movements.record(actor, new RecordMovement(
				apron, null, apronBatch, new BigDecimal("-0.5"), Unit.PIECES,
				MovementType.ISSUE, null, null, null, null, null, null)))
				.doesNotThrowAnyException();
	}

	// ---- The rows already written ----------------------------------------

	@Test
	@DisplayName("an apron sitting at 88.5 from before the rule still reads back, still sums, and is not rewritten")
	void theFractionalRowsAlreadyWrittenAreLeftAlone() throws Exception {
		// Exactly as staging holds it: a receipt of 88.5 aprons, written when nothing refused it.
		UUID oldBatch = UUID.randomUUID();
		UUID oldMovement = admin.queryForObject("""
				INSERT INTO stock_movements (
					tenant_id, ingredient_id, batch_id, quantity, unit, movement_type, actor_user_id)
				VALUES (?, ?, ?, 88.5, 'PIECES', 'PO_RECEIPT', ?) RETURNING id
				""", UUID.class, temple, apron, oldBatch, adminUser);

		// It reads back, and reads back as itself. Nothing in the rule is consulted on a read, which
		// is deliberate: a screen that throws is worse than one showing the bad row somebody needs in
		// order to correct it. Asserted on the figure rather than on the field merely existing — a
		// screen that quietly rounded 178.5 to 179 would pass an existence check and would be hiding
		// exactly the damage this task exists to stop.
		String body = mvc.perform(authed(get("/api/v1/inventory/items/{id}", apronItem), ADMIN_TOKEN))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertThat(new ObjectMapper().readTree(body).at("/item/onHand").decimalValue())
				.as("90 seeded plus the 88.5 nobody can enter any more, shown as it is stored")
				.isEqualByComparingTo("178.5");

		// It sums. 90 seeded plus the 88.5 nobody can enter any more.
		assertThat(onHandBase(apron)).isEqualByComparingTo("178.5");

		// A whole adjustment on top of it still works, and the half is carried, not swallowed.
		mvc.perform(adjust(apronItem, oldBatch, "-2", "PIECES")).andExpect(status().isCreated());
		assertThat(onHandBase(apron)).isEqualByComparingTo("176.5");

		// And the row itself was never touched. It is append-only, so this is belt and braces — but
		// the claim "existing rows stay exactly as they are" is worth asserting rather than assuming.
		assertThat(admin.queryForObject(
				"SELECT quantity FROM stock_movements WHERE id = ?", BigDecimal.class, oldMovement))
				.isEqualByComparingTo("88.5");
	}

	@Test
	@DisplayName("the fraction on an old row comes off through a correction, which is past validation on purpose")
	void anOldFractionCanStillBeReversed() {
		AuthenticatedUser actor = actor();
		UUID oldBatch = UUID.randomUUID();
		UUID oldMovement = admin.queryForObject("""
				INSERT INTO stock_movements (
					tenant_id, ingredient_id, batch_id, quantity, unit, movement_type, actor_user_id)
				VALUES (?, ?, ?, 1.5, 'PIECES', 'PO_RECEIPT', ?) RETURNING id
				""", UUID.class, temple, apron, oldBatch, adminUser);

		// This is the way out, and it is why no exception for "return exactly what was recorded" was
		// added to the doors. A correction asserts nothing new about the world; it nets a row that
		// should not be there back to zero, and StockMovementService.compensate goes straight to the
		// insert for exactly this case.
		movements.compensate(actor, oldMovement, "Recorded in error before whole counts were enforced");

		assertThat(onHandBase(apron)).as("the 1.5 is back off the books").isEqualByComparingTo("90");
	}

	// ---------------------------------------------------------------------

	/**
	 * The signed-in Temple Admin, as a service argument.
	 *
	 * <p>Sets the tenant context and leaves it set, because every service call that follows goes
	 * through the application's own unprivileged connection: without it row-level security hides the
	 * user, the ingredient and the movement alike, and the refusal under test never gets the chance
	 * to be the one that answers. {@link #tearDown()} clears it, so it cannot leak into the next test
	 * through the container this class shares with every other.
	 */
	private AuthenticatedUser actor() {
		TenantContext.set(temple);
		return new AuthenticatedUser(users.findById(adminUser).orElseThrow());
	}

	/** KMS-400191, with exactly one field error, naming the thing and the two whole numbers. */
	private static void refusedNaming(ResultActions result, String thing, String message) throws Exception {
		result.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400191"))
				.andExpect(jsonPath("$.fieldErrors.length()").value(1))
				.andExpect(jsonPath("$.fieldErrors[0].field").value(thing))
				.andExpect(jsonPath("$.fieldErrors[0].message").value(message));
	}

	// ---- Driving each door ----------------------------------------------

	private MockHttpServletRequestBuilder adjust(UUID itemId, UUID batchId, String quantity, String unit) {
		return authed(post("/api/v1/inventory/items/{id}/adjustments", itemId), ADMIN_TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content(("{\"batchId\":\"%s\",\"quantity\":%s,\"unit\":\"%s\",\"reason\":\"COUNT_CORRECTION\","
						+ "\"pricePerUnit\":40}").formatted(batchId, quantity, unit));
	}

	private MockHttpServletRequestBuilder createOrder(String lines) {
		return authed(post("/api/v1/purchase-orders"), ADMIN_TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"vendorId\":\"%s\",\"neededBy\":\"%s\",\"lines\":[%s]}"
						.formatted(vendor, LocalDate.now().plusDays(3), lines));
	}

	private static String orderLine(UUID ingredientId, String quantity, String unit) {
		return "{\"ingredientId\":\"%s\",\"quantity\":%s,\"unit\":\"%s\",\"expectedPrice\":40}"
				.formatted(ingredientId, quantity, unit);
	}

	private MockHttpServletRequestBuilder receive(UUID poId, UUID poLineId, String received, String rejected) {
		return authed(post("/api/v1/purchase-orders/{poId}/receipts", poId), ADMIN_TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content(("{\"idempotencyKey\":\"%s\",\"lines\":[{\"poLineId\":\"%s\","
						+ "\"receivedQty\":%s,\"rejectedQty\":%s%s}]}")
						.formatted(UUID.randomUUID(), poLineId, received, rejected,
								new BigDecimal(rejected).signum() > 0 ? ",\"rejectReason\":\"DAMAGED\"" : ""));
	}

	private MockHttpServletRequestBuilder returnGoods(UUID receiptId, UUID receiptLineId, String quantity) {
		return authed(post("/api/v1/goods-receipts/{receiptId}/returns", receiptId), ADMIN_TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content(("{\"idempotencyKey\":\"%s\",\"receiptLineId\":\"%s\",\"quantity\":%s,"
						+ "\"reason\":\"DAMAGED\"}").formatted(UUID.randomUUID(), receiptLineId, quantity));
	}

	private MockHttpServletRequestBuilder addShoppingLine(UUID ingredientId, String quantity) {
		return authed(post("/api/v1/shopping-list"), ADMIN_TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"ingredientId\":\"%s\",\"suggestedQty\":%s}".formatted(ingredientId, quantity));
	}

	private MockHttpServletRequestBuilder addPackSize(UUID ingredientId, String name, String qty, String unit) {
		return authed(post("/api/v1/ingredients/{id}/pack-sizes", ingredientId), ADMIN_TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"%s\",\"quantity\":%s,\"unit\":\"%s\"}".formatted(name, qty, unit));
	}

	private MockHttpServletRequestBuilder gift(String lines) {
		return authed(post("/api/v1/donations"), COOK_TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"anonymous\":true,\"donatedOn\":\"2026-08-10\",\"ingredients\":[%s]}".formatted(lines));
	}

	private static String giftLine(UUID ingredientId, String quantity, String unit) {
		return "{\"ingredientId\":\"%s\",\"quantity\":%s,\"unit\":\"%s\"}".formatted(ingredientId, quantity, unit);
	}

	private MockHttpServletRequestBuilder createRequest(String lines) {
		return createRequest(lines, "200", "PIECES");
	}

	private MockHttpServletRequestBuilder createRequest(String lines, String dishQty, String dishUnit) {
		return authed(post("/api/v1/ingredient-requests"), COOK_TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content(("{\"kitchenId\":\"%s\",\"neededOn\":\"%s\",\"purpose\":\"Janmashtami feast\","
						+ "\"lines\":[%s],"
						+ "\"dishes\":[{\"dishName\":\"Idli\",\"quantity\":%s,\"unit\":\"%s\"}]}")
						.formatted(kitchen, LocalDate.now().plusDays(2), lines, dishQty, dishUnit));
	}

	private static String requestLine(UUID ingredientId, String quantity, String unit) {
		return "{\"ingredientId\":\"%s\",\"quantity\":%s,\"unit\":\"%s\"}".formatted(ingredientId, quantity, unit);
	}

	// ---- Fixtures and reads ----------------------------------------------

	/** An order for twelve aprons, sent, so a delivery can be received against it. */
	private Ordered sentOrderForAprons() throws Exception {
		String poId = created(mvc.perform(createOrder(orderLine(apron, "12", "PIECES"))));
		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/send", poId), ADMIN_TOKEN))
				.andExpect(status().isNoContent());
		UUID lineId = admin.queryForObject(
				"SELECT id FROM purchase_order_lines WHERE po_id = ?::uuid", UUID.class, poId);
		return new Ordered(UUID.fromString(poId), lineId);
	}

	private record Ordered(UUID poId, UUID lineId) {
	}

	/**
	 * The one delivery just recorded, read from the tables rather than scraped out of the response.
	 * A receipt's JSON nests its lines, each of which has an "id" of its own, so a regex over the
	 * body picks one of those as often as it picks the receipt.
	 */
	private Received receipt() {
		UUID receiptId = admin.queryForObject("SELECT id FROM goods_receipts", UUID.class);
		UUID lineId = admin.queryForObject(
				"SELECT id FROM goods_receipt_lines WHERE receipt_id = ?", UUID.class, receiptId);
		return new Received(receiptId, lineId);
	}

	private record Received(UUID receiptId, UUID lineId) {
	}

	private static String created(ResultActions result) throws Exception {
		String response = result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return response.replaceAll(".*\"id\"\\s*:\\s*\"([0-9a-f-]+)\".*", "$1");
	}

	private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b, String token) {
		return b.header("Authorization", token);
	}

	private UUID insertUser(String uid, String name, String role, String phone) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE') RETURNING id
				""", UUID.class, temple, uid, name, uid + "@example.com", phone, role);
	}

	private UUID insertIngredient(String name, String unit, String category) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, ?, ?) RETURNING id
				""", UUID.class, temple, name, category, unit);
	}

	private UUID insertItem(UUID ingredientId) {
		return admin.queryForObject("""
				INSERT INTO inventory_items (tenant_id, ingredient_id, storage_location)
				VALUES (?, ?, 'Main store') RETURNING id
				""", UUID.class, temple, ingredientId);
	}

	private void seedMovement(UUID ingredientId, UUID batchId, String quantity, String unit) {
		admin.update("""
				INSERT INTO stock_movements (
					tenant_id, ingredient_id, batch_id, quantity, unit, movement_type, actor_user_id)
				VALUES (?, ?, ?, CAST(? AS numeric), ?, 'PO_RECEIPT', ?)
				""", temple, ingredientId, batchId, quantity, unit, adminUser);
	}

	private int count(String table) {
		Integer c = admin.queryForObject("SELECT count(*) FROM " + table, Integer.class);
		return c == null ? 0 : c;
	}

	private int movementCount() {
		return count("stock_movements");
	}

	private BigDecimal onHandBase(UUID ingredientId) {
		return admin.queryForObject(
				"SELECT COALESCE(SUM(to_base_qty(quantity, unit)), 0) FROM stock_movements WHERE ingredient_id = ?",
				BigDecimal.class, ingredientId);
	}
}
