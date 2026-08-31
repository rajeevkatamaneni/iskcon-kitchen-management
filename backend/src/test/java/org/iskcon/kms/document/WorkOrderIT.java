package org.iskcon.kms.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
import org.iskcon.kms.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The work order (E10-S11): the sheet a storekeeper carries round the store room, and the one that
 * comes back with two signatures on it.
 *
 * <p>What is worth proving here is what the story argued about. The sheet has to carry both halves
 * of the audit — the ingredients drawn <em>and</em> the dishes they were drawn to cook — because an
 * auditor spotting over-provisioning compares the two and a sheet with one of them cannot be
 * audited. The lots have to be named in the order they go off, and they have to be the same lots
 * issuing will actually take, which is proved by issuing and comparing rather than by reading the
 * allocator's own opinion back to itself. And the list has to be worked out when the sheet is
 * rendered rather than frozen at approval, which is proved by spoiling a lot and printing again.
 *
 * <p>The store room is seeded straight into {@code stock_movements}, because that is where stock
 * actually lives — every balance in this application is the sum of those rows.
 *
 * <p>A mocked {@link Scheduler} keeps the request→enqueue path hermetic; the worker step is driven
 * synchronously through {@link DocumentGenerationService}, as the other document tests do.
 */
@AutoConfigureMockMvc
@Import(WorkOrderIT.StubVerifierConfiguration.class)
class WorkOrderIT extends AbstractIntegrationTest {

	/** The day every request in this test is wanted for — a Tuesday, and printed as one. */
	private static final LocalDate WANTED = LocalDate.of(2026, 9, 15);

	@Autowired
	private MockMvc mvc;

	@Autowired
	private DocumentGenerationService generationService;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@MockBean
	private Scheduler scheduler;

	private JdbcTemplate admin;
	private UUID templeA;
	private UUID adminA;
	private UUID templeB;
	private UUID kitchenA;
	private UUID kitchenB;
	private UUID rice;
	private UUID dal;
	private UUID riceInHindi;
	private UUID riceB;

	@BeforeEach
	void setUp() {
		TenantContext.clear();
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();

		templeA = insertTenant("radha-govinda", "Sri Sri Radha Govinda Temple");
		adminA = insertUser(templeA, "uid-admin-a", "Radha Devi", "TEMPLE_ADMIN");
		insertUser(templeA, "uid-cook-a", "Bhakta Shyam", "KITCHEN_STAFF");
		kitchenA = insertKitchen(templeA, "Deity kitchen", "North wing, ground floor");

		templeB = insertTenant("mayapur", "Sri Mayapur Chandrodaya Temple");
		insertUser(templeB, "uid-admin-b", "Gopal Das", "TEMPLE_ADMIN");
		kitchenB = insertKitchen(templeB, "Prasadam kitchen", null);

		rice = insertIngredient(templeA, "Rice", "KG");
		dal = insertIngredient(templeA, "Toor dal", "KG");
		// Named in Devanagari, as a temple that works in Hindi names it. Nothing translates this; it
		// is already tenant content in its own script, and it has to reach the page unmangled.
		riceInHindi = insertIngredient(templeA, "चावल", "KG");
		riceB = insertIngredient(templeB, "Rice", "KG");

		signIn("uid-admin-a");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM documents");
		admin.execute("DELETE FROM document_label_translations");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM ingredient_request_events");
		admin.execute("DELETE FROM ingredient_request_lines");
		admin.execute("DELETE FROM ingredient_request_dishes");
		admin.execute("DELETE FROM ingredient_requests");
		admin.execute("DELETE FROM ingredient_request_sequence");
		admin.execute("DELETE FROM kitchens");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("the sheet names the temple, the kitchen, the reference, the reason, the dishes and the ingredients")
	void theSheetCarriesBothHalvesOfTheAudit() throws Exception {
		seedBatch(rice, "50", LocalDate.of(2026, 12, 31));
		String id = approvedRequest(
				lines(line(rice, "12", "KG")),
				dishes(dish("Khichdi", "200", "SERVINGS"), dish("Payasam", "30", "L")));

		String html = print(id, null);

		assertThat(html)
				.contains("Sri Sri Radha Govinda Temple")
				.contains("Work order")
				// The kitchen it is for — the fact that distinguishes this sheet from every other
				// piece of paper in the store room.
				.contains("Deity kitchen · North wing, ground floor")
				.contains("IR-2026-0001")
				.contains("Tuesday 15 September 2026")
				.contains("Janmashtami feast")
				// Both halves of the comparison an auditor makes: 12 Kg of rice, against 200 servings
				// of khichdi. A sheet carrying one of them cannot be audited at all.
				.contains("What is being cooked")
				.contains("Khichdi")
				.contains("200 servings")
				.contains("Payasam")
				.contains("30 L")
				.contains("What to pick")
				.contains("Rice")
				.contains("12 Kg")
				// The requester and the approver, each by name and date.
				.contains("Requested by")
				.contains("Bhakta Shyam")
				.contains("Approved by")
				.contains("Radha Devi");
	}

	@Test
	@DisplayName("two ruled boxes are signed: the store hands over, the kitchen takes delivery")
	void twoSignatureBoxes() throws Exception {
		seedBatch(rice, "50", LocalDate.of(2026, 12, 31));
		String id = approvedRequest(lines(line(rice, "12", "KG")), dishes(dish("Khichdi", "200", "SERVINGS")));

		String html = print(id, null);

		// Signing is paper, as E4-S11 D1 settled for the job card: a screen in a store room at six in
		// the morning is a screen nobody touches.
		assertThat(html)
				.contains("Issued by")
				.contains("The store handed this over.")
				.contains("Received by")
				.contains("The kitchen took delivery.")
				.contains("Signature");
		assertThat(countOf(html, "class=\"sign\"")).isEqualTo(2);
	}

	@Test
	@DisplayName("the lots are named oldest-expiry-first, and are the lots issuing actually draws")
	void theLotsAreTheOnesIssuingWillDraw() throws Exception {
		UUID september = seedBatch(rice, "5", LocalDate.of(2026, 9, 30));
		UUID december = seedBatch(rice, "10", LocalDate.of(2026, 12, 31));
		UUID noExpiry = seedBatch(rice, "20", null);
		String id = approvedRequest(lines(line(rice, "12", "KG")), dishes(dish("Khichdi", "200", "SERVINGS")));

		String html = print(id, null);

		// September's lot spoils first, so it is emptied before anything else is touched, and the lot
		// with no expiry at all is last in the queue and is never reached.
		assertThat(html)
				.contains("It is expiring on 30 Sep 2026.")
				.contains("It is expiring on 31 Dec 2026.")
				.doesNotContain("No expiry date");
		assertThat(html.indexOf("30 Sep 2026")).isLessThan(html.indexOf("31 Dec 2026"));
		// A lot is named by the pair a storekeeper actually uses — when it goes off and when it came
		// in — because a hex batch id is not something anybody can recognise on a shelf.
		// The lot line is a sentence naming the ingredient it came from, not two abbreviated
		// column headings (Rajeev, 2026-08-31).
		assertThat(html).contains("from Rice delivered on 1 Jan 2026.");

		// The sheet said 5 Kg out of one lot and 7 Kg out of the next. Issuing is then done for real
		// and the ledger has to agree, because a picking list that disagrees with the drawdown is
		// worse than no picking list.
		assertThat(betweenLots(html)).containsExactly("5 Kg", "7 Kg");

		issue(id).andExpect(status().isNoContent());
		Map<UUID, BigDecimal> drawn = drawnByBatch(id);
		assertThat(drawn).containsOnlyKeys(september, december);
		assertThat(drawn.get(september)).isEqualByComparingTo("-5000");
		assertThat(drawn.get(december)).isEqualByComparingTo("-7000");
		assertThat(drawn).doesNotContainKey(noExpiry);
	}

	@Test
	@DisplayName("spoiling the earliest lot and printing again names a different one — the sheet is live, not frozen")
	void theSheetIsComputedWhenItIsPrinted() throws Exception {
		UUID september = seedBatch(rice, "5", LocalDate.of(2026, 9, 30));
		seedBatch(rice, "10", LocalDate.of(2026, 12, 31));
		seedBatch(rice, "20", null);
		String id = approvedRequest(lines(line(rice, "12", "KG")), dishes(dish("Khichdi", "200", "SERVINGS")));

		assertThat(print(id, null)).contains("It is expiring on 30 Sep 2026.");

		// The whole September sack goes off between one print and the next. Approval decided the
		// kitchen may have the food; the sheet says where today's is, and a work order that sends a
		// storekeeper to a bare shelf is worse than no work order (V79's header, design D3).
		spoil(september, rice, "5");

		String reprinted = print(id, null);
		assertThat(reprinted)
				.doesNotContain("It is expiring on 30 Sep 2026.")
				.contains("It is expiring on 31 Dec 2026.")
				// Ten kilos out of December's lot and the last two out of the one with no date on it,
				// which was never reached before.
				.contains("It has no expiry date.");
		assertThat(betweenLots(reprinted)).containsExactly("10 Kg", "2 Kg");
	}

	@Test
	@DisplayName("a line the store cannot cover says so on the sheet rather than at the shelf")
	void aShortLineIsSaidOutLoud() throws Exception {
		seedBatch(rice, "3", LocalDate.of(2026, 12, 31));
		String id = approvedRequest(lines(line(rice, "12", "KG")), dishes(dish("Khichdi", "200", "SERVINGS")));

		String html = print(id, null);

		// The lots there are still print — they are still the ones to pick — and the gap is said on
		// the row and again at the top, so nobody makes the walk in ignorance. Refusing the issue is
		// the ledger's business, not the sheet's.
		assertThat(html)
				.contains("Not enough on the shelf")
				// The banner at the top and the row itself say the same thing, because the banner is
				// built from the rows rather than passed in beside them.
				.contains("Rice &middot; Not enough on the shelf &middot; 3 Kg / 12 Kg")
				.contains("<div class=\"shortfall\">Not enough on the shelf &middot; 3 Kg / 12 Kg</div>")
				.contains("It is expiring on 31 Dec 2026.");
	}

	@Test
	@DisplayName("a quantity is the cook's figure: a 0.134 Kg line reads 135 gm, never 0.134 Kg")
	void quantitiesAreTheCooksForm() throws Exception {
		seedBatch(rice, "50", LocalDate.of(2026, 12, 31));
		String id = approvedRequest(
				lines(line(rice, "0.1344", "KG")), dishes(dish("Chutney", "2", "L")));

		String html = print(id, null);

		// A work order is weighed against, never reconciled against, so every figure on it is the
		// cook's form — nobody weighs a hundred and thirty-four point four grams of anything.
		assertThat(html)
				.contains("135 gm")
				.doesNotContain("0.134")
				.doesNotContain("0.1344");
	}

	@Test
	@DisplayName("a request nobody has approved has no work order — draft, submitted and denied alike")
	void onlyAnApprovedRequestHasAWorkOrder() throws Exception {
		seedBatch(rice, "50", LocalDate.of(2026, 12, 31));

		String draft = draftRequest(lines(line(rice, "12", "KG")), dishes(dish("Khichdi", "200", "SERVINGS")));
		printRaw(draft, null)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4981"));
		queueRaw(draft)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4981"));

		submit(draft);
		printRaw(draft, null).andExpect(jsonPath("$.code").value("KMS-4981"));

		mvc.perform(authed(post("/api/v1/ingredient-requests/{id}/deny", draft))
						.contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Not this week.\"}"))
				.andExpect(status().isNoContent());
		printRaw(draft, null).andExpect(jsonPath("$.code").value("KMS-4981"));

		// And nothing was queued along the way — a document row for a sheet that cannot be rendered
		// would turn a clear refusal into a FAILED row somebody has to interpret.
		assertThat(admin.queryForObject("SELECT count(*) FROM documents", Integer.class)).isZero();
	}

	@Test
	@DisplayName("an issued request keeps its sheet, so the paper that was signed can be reprinted")
	void anIssuedRequestKeepsItsSheet() throws Exception {
		seedBatch(rice, "50", LocalDate.of(2026, 12, 31));
		String id = approvedRequest(lines(line(rice, "12", "KG")), dishes(dish("Khichdi", "200", "SERVINGS")));
		issue(id).andExpect(status().isNoContent());

		assertThat(print(id, null)).contains("IR-2026-0001").contains("Khichdi");
	}

	@Test
	@DisplayName("the print view and the queued PDF are rendered from the same HTML")
	void printAndPdfAreTheSameSheet() throws Exception {
		seedBatch(rice, "50", LocalDate.of(2026, 12, 31));
		String id = approvedRequest(lines(line(rice, "12", "KG")), dishes(dish("Khichdi", "200", "SERVINGS")));

		String printed = print(id, null);
		UUID documentId = queue(id);
		generate(documentId);

		mvc.perform(authed(get("/api/v1/work-orders/documents/{id}", documentId)))
				.andExpect(jsonPath("$.status").value("READY"))
				.andExpect(jsonPath("$.version").value(1))
				.andExpect(jsonPath("$.kind").value("WORK_ORDER_PDF"));

		// The stub renderer records the length of the HTML it was handed, which is the one thing a
		// hermetic test can compare the two paths on without a browser. Same template, same model,
		// same language resolution — so the same document, to the character.
		String pdf = mvc.perform(authed(get("/api/v1/work-orders/documents/{id}/download", documentId)))
				.andExpect(status().isOk())
				.andExpect(content().contentType("application/pdf"))
				.andReturn().getResponse().getContentAsString();
		assertThat(pdf).contains("source HTML length: " + printed.length());

		// Versioned, not overwritten: a sheet reprinted after a lot was spoilt names different lots
		// from the one somebody in the store room is already holding, and the version says which.
		queue(id);
		mvc.perform(authed(get("/api/v1/work-orders/documents").param("requestId", id)))
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].version").value(2));
	}

	@Test
	@DisplayName("it renders in a non-Latin script without tofu")
	void itRendersInDevanagari() throws Exception {
		seedBatch(riceInHindi, "50", LocalDate.of(2026, 12, 31));
		String id = approvedRequest(
				lines(line(riceInHindi, "12", "KG")), dishes(dish("Khichdi", "200", "SERVINGS")));

		String html = print(id, "hi");

		// The sheet is declared UTF-8 and the ingredient's own Devanagari name reaches it unmangled.
		assertThat(html)
				.contains("<meta charset=\"utf-8\">")
				.contains("चावल")
				// The fixed wording and the tenant content both went through the translation path.
				.contains("[hi] Work order")
				.contains("[hi] Khichdi")
				// And the language is named in its own script, so somebody who does not read English
				// need not read "Hindi" to find out this is the Hindi copy.
				.contains("हिन्दी");

		// The Noto stack names a family per script rather than trusting Chromium's codepoint
		// fallback, which picks whatever fontconfig ranks first and can change with the base image.
		assertThat(html)
				.contains("'Noto Sans Devanagari'")
				.contains("'Noto Sans Kannada'")
				.contains("'Noto Sans Telugu'")
				.contains("'Noto Sans Tamil'")
				.contains("'Noto Sans Bengali'")
				.contains("'Noto Sans Malayalam'");
	}

	@Test
	@DisplayName("all 23 languages are offered, and the temple's own is the one the picker opens on")
	void everyLanguageIsOffered() throws Exception {
		languages()
				.andExpect(jsonPath("$.languages.length()").value(23))
				.andExpect(jsonPath("$.languages[0]").value("en"))
				.andExpect(jsonPath("$.defaultLanguage").value("en"));

		mvc.perform(authed(put("/api/v1/settings/language"))
						.contentType(MediaType.APPLICATION_JSON).content("{\"language\":\"kn\"}"))
				.andExpect(status().isNoContent());

		languages()
				.andExpect(jsonPath("$.languages.length()").value(23))
				.andExpect(jsonPath("$.defaultLanguage").value("kn"));

		// Nobody chose at the printer, and the sheet still comes out in the language the store room
		// reads — and English is still one choice away.
		seedBatch(rice, "50", LocalDate.of(2026, 12, 31));
		String id = approvedRequest(lines(line(rice, "12", "KG")), dishes(dish("Khichdi", "200", "SERVINGS")));
		assertThat(print(id, null)).contains("[kn] Rice").contains("[kn] Khichdi");
		assertThat(print(id, "en")).contains("Rice").doesNotContain("[kn]");
	}

	@Test
	@DisplayName("a self-approved request says so on the paper an auditor reads")
	void selfApprovalIsPrinted() throws Exception {
		seedBatch(rice, "50", LocalDate.of(2026, 12, 31));

		// Raised and approved by the same administrator — allowed, because forbidding it would
		// deadlock a temple whose administrator is its only approver, which is most of them.
		String id = draftRequest(lines(line(rice, "12", "KG")), dishes(dish("Khichdi", "200", "SERVINGS")));
		submit(id);
		approve(id);

		assertThat(print(id, null)).contains("Approved by the person who raised it.");
	}

	@Test
	@DisplayName("the sheet belongs to whoever opens the store room, not to whoever raised the request")
	void onlySomebodyWhoMayIssueMayPrintIt() throws Exception {
		seedBatch(rice, "50", LocalDate.of(2026, 12, 31));
		String id = approvedRequest(lines(line(rice, "12", "KG")), dishes(dish("Khichdi", "200", "SERVINGS")));

		// A work order is an instrument for moving stock, so it is behind the permission for moving
		// stock. The cook who raised it reads the request on its own screen instead.
		signIn("uid-cook-a");
		printRaw(id, null).andExpect(status().isForbidden());
		queueRaw(id).andExpect(status().isForbidden());
		mvc.perform(authed(get("/api/v1/work-orders/languages"))).andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("another temple's request has no work order here, because it is not visible at all")
	void oneTemplesSheetIsInvisibleToAnother() throws Exception {
		seedBatch(rice, "50", LocalDate.of(2026, 12, 31));
		String mine = approvedRequest(lines(line(rice, "12", "KG")), dishes(dish("Khichdi", "200", "SERVINGS")));

		signIn("uid-admin-b");
		// Not "you may not print this" — row-level security means the row is simply not there.
		printRaw(mine, null)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("KMS-4977"));
		mvc.perform(authed(get("/api/v1/work-orders/documents").param("requestId", mine)))
				.andExpect(jsonPath("$.length()").value(0));

		// And Mayapur's own request prints Mayapur's sheet, so this is isolation rather than an
		// endpoint that refuses everybody.
		seedBatchFor(templeB, riceB, "20", LocalDate.of(2026, 11, 30));
		String theirs = approvedRequestFor(kitchenB, lines(line(riceB, "5", "KG")),
				dishes(dish("Kitchari", "100", "SERVINGS")));
		assertThat(print(theirs, null))
				.contains("Sri Mayapur Chandrodaya Temple")
				.contains("Prasadam kitchen")
				.doesNotContain("Radha Govinda");
	}

	@Test
	@DisplayName("printing a work order moves no stock and changes nothing about the request")
	void printingIsARead() throws Exception {
		seedBatch(rice, "50", LocalDate.of(2026, 12, 31));
		String id = approvedRequest(lines(line(rice, "12", "KG")), dishes(dish("Khichdi", "200", "SERVINGS")));
		BigDecimal before = onHandBase(rice);

		print(id, null);
		generate(queue(id));

		// The sheet is a picking list, not the drawdown. Stock falls when the storekeeper records
		// what actually went over the counter, and not one moment earlier.
		assertThat(onHandBase(rice)).isEqualByComparingTo(before);
		assertThat(admin.queryForObject(
				"SELECT status FROM ingredient_requests WHERE id = ?::uuid", String.class, id))
				.isEqualTo("APPROVED");
		// Printing has never been an audited act in this system, for any of the four documents, and
		// this one does not invent the idea.
		assertThat(admin.queryForObject("SELECT count(*) FROM audit_events WHERE action LIKE '%DOCUMENT%'",
				Integer.class)).isZero();
	}

	@Test
	@DisplayName("a request naming the same ingredient twice is one row on the sheet, drawn once")
	void repeatedIngredientsAreOneRow() throws Exception {
		seedBatch(rice, "50", LocalDate.of(2026, 12, 31));
		seedBatch(dal, "50", LocalDate.of(2026, 12, 31));
		String id = approvedRequest(
				lines(line(rice, "8", "KG"), line(dal, "4", "KG"), line(rice, "4", "KG")),
				dishes(dish("Khichdi", "200", "SERVINGS")));

		String html = print(id, null);

		// Two rows each drawing independently would each see the whole of the earliest lot and
		// between them name more of it than it holds. Issuing merges the lines before it allocates,
		// and so does the sheet — which is what makes the sheet's answer the same answer.
		assertThat(countOf(html, "<span class=\"name\">Rice</span>")).isEqualTo(1);
		assertThat(html).contains("12 Kg").contains("4 Kg");
	}

	// ---------------------------------------------------------------------

	private String draftRequest(String lines, String dishes) throws Exception {
		return draftRequestFor(kitchenA, lines, dishes);
	}

	private String draftRequestFor(UUID kitchenId, String lines, String dishes) throws Exception {
		String json = ("{\"kitchenId\":\"%s\",\"neededOn\":\"%s\",\"purpose\":\"Janmashtami feast\","
				+ "\"lines\":[%s],\"dishes\":[%s]}").formatted(kitchenId, WANTED, lines, dishes);
		String response = mvc.perform(authed(post("/api/v1/ingredient-requests"))
						.contentType(MediaType.APPLICATION_JSON).content(json))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return response.replaceAll(".*\"id\"\\s*:\\s*\"([0-9a-f-]+)\".*", "$1");
	}

	/** Raised by the cook and answered by the administrator, so the two names on the sheet differ. */
	private String approvedRequest(String lines, String dishes) throws Exception {
		signIn("uid-cook-a");
		String id = draftRequest(lines, dishes);
		submit(id);
		signIn("uid-admin-a");
		approve(id);
		return id;
	}

	private String approvedRequestFor(UUID kitchenId, String lines, String dishes) throws Exception {
		String id = draftRequestFor(kitchenId, lines, dishes);
		submit(id);
		approve(id);
		return id;
	}

	private void submit(String id) throws Exception {
		mvc.perform(authed(post("/api/v1/ingredient-requests/{id}/submit", id)))
				.andExpect(status().isNoContent());
	}

	private void approve(String id) throws Exception {
		mvc.perform(authed(post("/api/v1/ingredient-requests/{id}/approve", id))
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isNoContent());
	}

	private org.springframework.test.web.servlet.ResultActions issue(String id) throws Exception {
		return mvc.perform(authed(post("/api/v1/ingredient-requests/{id}/issue", id))
				.contentType(MediaType.APPLICATION_JSON).content("{}"));
	}

	private org.springframework.test.web.servlet.ResultActions printRaw(String id, String language)
			throws Exception {
		var request = authed(get("/api/v1/work-orders/print")).param("requestId", id);
		if (language != null) {
			request = request.param("language", language);
		}
		return mvc.perform(request);
	}

	private String print(String id, String language) throws Exception {
		return printRaw(id, language)
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith("text/html"))
				.andReturn().getResponse().getContentAsString();
	}

	private org.springframework.test.web.servlet.ResultActions queueRaw(String id) throws Exception {
		return mvc.perform(authed(post("/api/v1/work-orders")).param("requestId", id));
	}

	private UUID queue(String id) throws Exception {
		String body = queueRaw(id)
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andReturn().getResponse().getContentAsString();
		return UUID.fromString(body.replaceAll(".*\"documentId\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
	}

	private void generate(UUID documentId) {
		UUID tenant = admin.queryForObject(
				"SELECT tenant_id FROM documents WHERE id = ?", UUID.class, documentId);
		TenantContext.set(tenant);
		try {
			generationService.generate(documentId);
		} finally {
			TenantContext.clear();
		}
	}

	private org.springframework.test.web.servlet.ResultActions languages() throws Exception {
		return mvc.perform(authed(get("/api/v1/work-orders/languages"))).andExpect(status().isOk());
	}

	/** The amounts the sheet says to take out of each lot, in the order it lists them. */
	private static List<String> betweenLots(String html) {
		List<String> takes = new java.util.ArrayList<>();
		String open = "<span class=\"take\">";
		int at = html.indexOf(open);
		while (at >= 0) {
			int from = at + open.length();
			takes.add(html.substring(from, html.indexOf("</span>", from)));
			at = html.indexOf(open, from);
		}
		return takes;
	}

	private static String lines(String... lines) {
		return String.join(",", lines);
	}

	private static String dishes(String... dishes) {
		return String.join(",", dishes);
	}

	private static String line(UUID ingredientId, String quantity, String unit) {
		return "{\"ingredientId\":\"%s\",\"quantity\":%s,\"unit\":\"%s\"}"
				.formatted(ingredientId, quantity, unit);
	}

	private static String dish(String name, String quantity, String unit) {
		return "{\"dishName\":\"%s\",\"quantity\":%s,\"unit\":\"%s\"}".formatted(name, quantity, unit);
	}

	/** How much this issue took out of each batch. Keyed by batch, because row order is a shuffle. */
	private Map<UUID, BigDecimal> drawnByBatch(String requestId) {
		Map<UUID, BigDecimal> drawn = new LinkedHashMap<>();
		for (Map<String, Object> row : admin.queryForList("""
				SELECT batch_id, quantity FROM stock_movements
				WHERE reference_type = 'INGREDIENT_REQUEST' AND reference_id = ?::uuid
				""", requestId)) {
			drawn.merge((UUID) row.get("batch_id"), (BigDecimal) row.get("quantity"), BigDecimal::add);
		}
		return drawn;
	}

	private BigDecimal onHandBase(UUID ingredientId) {
		BigDecimal sum = admin.queryForObject("""
				SELECT COALESCE(SUM(to_base_qty(quantity, unit)), 0)
				FROM stock_movements WHERE ingredient_id = ?
				""", BigDecimal.class, ingredientId);
		return sum == null ? BigDecimal.ZERO : sum;
	}

	private UUID seedBatch(UUID ingredientId, String quantity, LocalDate expiry) {
		return seedBatchFor(templeA, ingredientId, quantity, expiry);
	}

	private UUID seedBatchFor(UUID tenantId, UUID ingredientId, String quantity, LocalDate expiry) {
		UUID batch = UUID.randomUUID();
		String unit = admin.queryForObject(
				"SELECT canonical_unit FROM ingredients WHERE id = ?", String.class, ingredientId);
		admin.update("""
				INSERT INTO stock_movements (
					tenant_id, ingredient_id, batch_id, quantity, unit, movement_type,
					expiry_date, received_date, actor_user_id)
				VALUES (?, ?, ?, ?::numeric, ?, 'PO_RECEIPT', ?, DATE '2026-01-01',
						(SELECT id FROM users WHERE tenant_id = ? LIMIT 1))
				""", tenantId, ingredientId, batch, quantity, unit, expiry, tenantId);
		return batch;
	}

	/** The whole sack goes off — recorded the way spoilage always is, as a negative adjustment. */
	private void spoil(UUID batch, UUID ingredientId, String quantity) {
		String unit = admin.queryForObject(
				"SELECT canonical_unit FROM ingredients WHERE id = ?", String.class, ingredientId);
		admin.update("""
				INSERT INTO stock_movements (
					tenant_id, ingredient_id, batch_id, quantity, unit, movement_type,
					reason_category, actor_user_id)
				VALUES (?, ?, ?, -?::numeric, ?, 'ADJUSTMENT', 'SPOILAGE', ?)
				""", templeA, ingredientId, batch, quantity, unit, adminA);
	}

	private static int countOf(String haystack, String needle) {
		int count = 0;
		int at = haystack.indexOf(needle);
		while (at >= 0) {
			count++;
			at = haystack.indexOf(needle, at + needle.length());
		}
		return count;
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
		return builder.header("Authorization", "Bearer valid-token");
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}

	private UUID insertTenant(String slug, String name) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata') RETURNING id
				""", UUID.class, slug, name);
	}

	private UUID insertUser(UUID tenantId, String uid, String fullName, String role) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, ?, ?, '+919876500081', ?, 'ACTIVE') RETURNING id
				""", UUID.class, tenantId, uid, fullName, uid + "@example.com", role);
	}

	private UUID insertKitchen(UUID tenantId, String name, String location) {
		return admin.queryForObject("""
				INSERT INTO kitchens (tenant_id, name, location, created_by)
				VALUES (?, ?, ?, (SELECT id FROM users WHERE tenant_id = ? LIMIT 1)) RETURNING id
				""", UUID.class, tenantId, name, location, tenantId);
	}

	private UUID insertIngredient(UUID tenantId, String name, String unit) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Grains', ?) RETURNING id
				""", UUID.class, tenantId, name, unit);
	}

	// ---------------------------------------------------------------------

	@TestConfiguration
	static class StubVerifierConfiguration {

		@Bean
		@Primary
		StubTokenVerifier stubTokenVerifier() {
			return new StubTokenVerifier();
		}
	}

	static class StubTokenVerifier implements TokenVerifier {

		private final Map<String, VerifiedSubject> accepted = new HashMap<>();

		void accept(String uid) {
			accepted.put("valid-token", new VerifiedSubject(uid, uid + "@example.com", "+919000000000"));
		}

		void reset() {
			accepted.clear();
		}

		@Override
		public VerifiedSubject verify(String idToken) throws InvalidTokenException {
			VerifiedSubject subject = accepted.get(idToken);
			if (subject == null) {
				throw new InvalidTokenException("Unrecognised token");
			}
			return subject;
		}
	}
}
