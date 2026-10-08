package org.iskcon.kms.assistant.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.assistant.mcp.PurchaseOrderTools;
import org.iskcon.kms.assistant.token.AgentPrincipal;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.auth.TokenVerifier.VerifiedSubject;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.testsupport.StubTokenVerifier;
import org.iskcon.kms.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Assistant drafts end to end (E10-S4): the propose tool stores a draft purchase order under the
 * agent's identity, and the person confirms or rejects it over HTTP with their own sign-in, through
 * the real controller, the real {@code PurchaseOrderService} and a real database under RLS.
 *
 * <p>The draft is made by calling the {@link PurchaseOrderTools} bean with the temple and the agent's
 * authentication on the thread, exactly where the MCP scope puts them (as {@code InventoryToolsIT}
 * does), so {@code @PreAuthorize} on the tool is the real one.
 *
 * <p><strong>No context of its own</strong>: no {@code @Import}, property or mock, so it shares the
 * suite's common context.
 */
class AssistantActionIT extends AbstractIntegrationTest {

	private static final String ADMIN_A = "uid-act-admin-a";
	private static final String MANAGER_A = "uid-act-manager-a";
	private static final String VOLUNTEER_A = "uid-act-volunteer-a";
	private static final String ADMIN_B = "uid-act-admin-b";
	private static final String RICE_KN = "ಅಕ್ಕಿ";
	private static final String RICE_TE = "సోనా మసూరి బియ్యం";

	@LocalServerPort
	private int port;

	@Autowired
	private StubTokenVerifier verifier;

	@Autowired
	private PurchaseOrderTools tools;

	@Autowired
	private PendingActionService actions;

	@Autowired
	private UserRepository users;

	@Autowired
	private ObjectMapper json;

	private final HttpClient http = HttpClient.newHttpClient();
	private JdbcTemplate admin;

	private UUID templeA;
	private UUID templeB;
	private UUID adminA;
	private UUID vendor;
	private UUID rice;
	private UUID sugar;
	private UUID dal;
	private UUID conversation;
	private UUID run;

	@BeforeEach
	void setUp() {
		TenantContext.clear();
		SecurityContextHolder.clearContext();
		admin = new JdbcTemplate(adminDataSource());
		templeA = tenant("act-a", "Action Temple A");
		templeB = tenant("act-b", "Action Temple B");
		adminA = user(templeA, ADMIN_A, "TEMPLE_ADMIN", "+919876500301");
		user(templeA, MANAGER_A, "KITCHEN_MANAGER", "+919876500302");
		user(templeA, VOLUNTEER_A, "VOLUNTEER", "+919876500303");
		user(templeB, ADMIN_B, "TEMPLE_ADMIN", "+919876500304");
		for (String uid : List.of(ADMIN_A, MANAGER_A, VOLUNTEER_A, ADMIN_B)) {
			verifier.accept(uid, new VerifiedSubject(uid, uid + "@example.com", null, false));
		}

		vendor = admin.queryForObject("""
				INSERT INTO vendors (tenant_id, name, phone) VALUES (?, 'Sri Balaji Traders', '+919812345678')
				RETURNING id
				""", UUID.class, templeA);
		admin.queryForObject("""
				INSERT INTO vendors (tenant_id, name, phone) VALUES (?, 'Heritage Fresh Dairy', '+919812345679')
				RETURNING id
				""", UUID.class, templeA);
		rice = ingredient("Rice, Sona Masoori", "KG");
		sugar = ingredient("Sugar", "GM");
		dal = ingredient("Toor Dal", "KG");
		// Rice ₹45 / Kg; sugar kept in grams at ₹0.05 / gm, which the card must say as ₹50 / Kg.
		supply(rice, "45");
		supply(sugar, "0.05");
		supply(dal, "120");
		admin.update("""
				INSERT INTO translation_glossary (tenant_id, language, source_term, target_term)
				VALUES (?, 'kn', 'Rice, Sona Masoori', ?)
				""", templeA, RICE_KN);
		// What the list asks for: 25 Kg of rice and 2500 gm of sugar, ticked; dal unticked.
		listLine(rice, "25", "KG", true);
		listLine(sugar, "2500", "GM", true);
		listLine(dal, "10", "KG", false);

		conversation = admin.queryForObject("""
				INSERT INTO assistant_conversations (tenant_id, user_id, language) VALUES (?, ?, 'kn') RETURNING id
				""", UUID.class, templeA, adminA);
		run = admin.queryForObject("""
				INSERT INTO assistant_runs (tenant_id, conversation_id, user_id, model) VALUES (?, ?, ?, 'sonnet')
				RETURNING id
				""", UUID.class, templeA, conversation, adminA);
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		SecurityContextHolder.clearContext();
		admin.update("DELETE FROM assistant_pending_actions");
		admin.update("DELETE FROM assistant_messages");
		admin.update("DELETE FROM assistant_runs");
		admin.update("DELETE FROM assistant_conversations");
		admin.execute("DELETE FROM po_events");
		admin.execute("DELETE FROM purchase_order_lines");
		admin.execute("DELETE FROM purchase_orders");
		admin.execute("DELETE FROM po_sequence");
		admin.execute("DELETE FROM shopping_list_lines");
		admin.execute("DELETE FROM vendor_supplies");
		admin.execute("DELETE FROM vendors");
		admin.execute("DELETE FROM translation_glossary");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ---- Propose ------------------------------------------------------------

	@Test
	@DisplayName("propose: the vendor's ticked shopping-list lines, the card built server-side, nothing ordered")
	@SuppressWarnings("unchecked")
	void proposeFromShoppingList() {
		Map<String, Object> answer = propose("balaji", null, null);

		UUID actionId = (UUID) answer.get("draft_id");
		assertThat(answer.get("vendor")).isEqualTo("Sri Balaji Traders");
		assertThat(answer.get("status")).isEqualTo("DRAFT_READY_TO_CONFIRM");
		assertThat(admin.queryForObject("SELECT count(*) FROM purchase_orders", Integer.class)).isZero();

		Map<String, Object> row = admin.queryForMap(
				"SELECT kind, status, run_id, conversation_id, payload_sha256, expires_at > now() + interval '23 hours' AS day"
						+ " FROM assistant_pending_actions WHERE id = ?", actionId);
		assertThat(row).containsEntry("kind", "PURCHASE_ORDER").containsEntry("status", "PROPOSED")
				.containsEntry("run_id", run).containsEntry("conversation_id", conversation).containsEntry("day", true);

		AssistantProposal card = cards().get(0);
		assertThat(card.title()).isEqualTo("Purchase order for Sri Balaji Traders");
		assertThat(card.confirmSays()).isEqualTo("Confirm saves a draft order. Nothing is sent.").hasSizeLessThanOrEqualTo(45);
		assertThat(card.lines()).extracting(AssistantProposal.Line::name).containsExactly("Rice, Sona Masoori", "Sugar");
		AssistantProposal.Line riceLine = card.lines().get(0);
		assertThat(riceLine.localName()).isEqualTo(RICE_KN);
		assertThat(riceLine.quantity()).isEqualByComparingTo("25");
		assertThat(riceLine.unit()).isEqualTo("Kg");
		assertThat(riceLine.rate()).isEqualByComparingTo("45");
		assertThat(riceLine.amount()).isEqualByComparingTo("1125");
		AssistantProposal.Line sugarLine = card.lines().get(1);
		assertThat(sugarLine.localName()).isNull();
		List<Map<String, Object>> answerLines = (List<Map<String, Object>>) answer.get("lines");
		assertThat(answerLines.get(0)).containsEntry("name_local", RICE_KN);
		assertThat(answerLines.get(1)).containsEntry("name_pending", true).doesNotContainKey("name_local");
		assertThat(sugarLine.quantity()).isEqualByComparingTo("2.5");
		assertThat(sugarLine.unit()).isEqualTo("Kg");
		assertThat(sugarLine.rate()).as("₹0.05 / gm said per Kg").isEqualByComparingTo("50");
		assertThat(sugarLine.amount()).isEqualByComparingTo("125");
		assertThat(card.facts()).extracting(AssistantProposal.Fact::label).containsExactly("Vendor", "Needed by", "Total");
		assertThat(card.facts().get(2).value()).isEqualTo("₹1,250");
	}

	@Test
	@DisplayName("the card, the tool's lines and the shopping list name items in the conversation's language: Telugu here, not the Kannada also stored")
	@SuppressWarnings("unchecked")
	void namesFollowTheConversationLanguage() {
		admin.update("UPDATE assistant_conversations SET language = 'te' WHERE id = ?", conversation);
		admin.update("""
				INSERT INTO translation_glossary (tenant_id, language, source_term, target_term)
				VALUES (?, 'te', 'Rice, Sona Masoori', ?)
				""", templeA, RICE_TE);

		Map<String, Object> answer = propose("balaji", null, null);

		AssistantProposal card = cards().get(0);
		assertThat(card.lines()).extracting(AssistantProposal.Line::localName).containsExactly(RICE_TE, null);
		List<Map<String, Object>> lines = (List<Map<String, Object>>) answer.get("lines");
		assertThat(lines.get(0)).containsEntry("name_local", RICE_TE).doesNotContainKey("name_pending");
		assertThat(lines.get(1)).as("sugar has no Telugu name yet").containsEntry("name_pending", true)
				.doesNotContainKey("name_local");

		actAsAgent("agent:read", "MANAGE_PURCHASE_ORDERS");
		Map<String, Object> list;
		try {
			list = tools.getShoppingList();
		} finally {
			TenantContext.clear();
			SecurityContextHolder.clearContext();
		}
		List<Map<String, Object>> listed = ((List<Map<String, Object>>) list.get("vendors")).stream()
				.flatMap(v -> ((List<Map<String, Object>>) v.get("lines")).stream()).toList();
		assertThat(listed).filteredOn(l -> "Rice, Sona Masoori".equals(l.get("name")))
				.singleElement().satisfies(l -> assertThat(l).containsEntry("name_local", RICE_TE));
		assertThat(listed).filteredOn(l -> "Sugar".equals(l.get("name")))
				.singleElement().satisfies(l -> assertThat(l).containsEntry("name_pending", true));
		assertThat(listed).noneMatch(l -> RICE_KN.equals(l.get("name_local")));
	}

	@Test
	@DisplayName("in an English conversation the card and the tool carry no local name and nothing pending")
	@SuppressWarnings("unchecked")
	void englishConversationHasNoLocalNames() {
		admin.update("UPDATE assistant_conversations SET language = 'en' WHERE id = ?", conversation);

		Map<String, Object> answer = propose("balaji", null, null);

		assertThat(cards().get(0).lines()).extracting(AssistantProposal.Line::localName).containsOnlyNulls();
		for (Map<String, Object> line : (List<Map<String, Object>>) answer.get("lines")) {
			assertThat(line).doesNotContainKeys("name_local", "name_pending");
		}
	}

	@Test
	@DisplayName("propose: a retried call in the same run gives the same draft, not a second card")
	void proposeIsIdempotentWithinARun() {
		UUID first = (UUID) propose("Sri Balaji Traders", null, null).get("draft_id");
		UUID second = (UUID) propose("sri balaji traders", null, null).get("draft_id");

		assertThat(second).isEqualTo(first);
		assertThat(admin.queryForObject("SELECT count(*) FROM assistant_pending_actions", Integer.class)).isEqualTo(1);
	}

	@Test
	@DisplayName("propose: lines the person named are matched to the catalogue; a wrong unit or vendor is refused in words")
	void proposeNamedLines() {
		Map<String, Object> answer = propose("Sri Balaji Traders",
				List.of(Map.of("item", "toor dal", "quantity", 5, "unit", "kg")), "2099-01-31");
		assertThat(answer.get("needed_by").toString()).isEqualTo("2099-01-31");
		AssistantProposal card = cards().get(0);
		assertThat(card.lines()).hasSize(1);
		assertThat(card.lines().get(0).name()).isEqualTo("Toor Dal");
		assertThat(card.lines().get(0).amount()).isEqualByComparingTo("600");

		assertThatThrownBy(() -> propose("Sri Balaji Traders",
				List.of(Map.of("item", "toor dal", "quantity", 5, "unit", "litre")), null))
				.hasMessageContaining("Give the quantity of Toor Dal in Kg or gm");
		assertThatThrownBy(() -> propose("Nobody Stores", null, null))
				.hasMessageContaining("No active vendor is called");
		assertThatThrownBy(() -> propose("Sri Balaji Traders", null, "2000-01-01"))
				.hasMessageContaining("has already passed");
	}

	@Test
	@DisplayName("propose needs agent:propose as well as MANAGE_PURCHASE_ORDERS")
	void proposeNeedsTheProposeScope() {
		actAsAgent("agent:read", "MANAGE_PURCHASE_ORDERS");
		assertThatThrownBy(() -> tools.proposePurchaseOrder("Sri Balaji Traders", null, null))
				.isInstanceOf(AccessDeniedException.class);
		actAsAgent("agent:read", "agent:propose");
		assertThatThrownBy(() -> tools.proposePurchaseOrder("Sri Balaji Traders", null, null))
				.isInstanceOf(AccessDeniedException.class);
		assertThat(admin.queryForObject("SELECT count(*) FROM assistant_pending_actions", Integer.class)).isZero();
	}

	// ---- Confirm --------------------------------------------------------------

	@Test
	@DisplayName("confirm: exactly one DRAFT order with the right lines; a second confirm runs nothing and answers the same")
	void confirmCreatesOneOrder() throws Exception {
		UUID actionId = (UUID) propose("balaji", null, null).get("draft_id");

		HttpResponse<String> first = post(ADMIN_A, "/api/v1/assistant/actions/" + actionId + "/confirm");
		assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
		JsonNode confirmed = json.readTree(first.body());
		assertThat(confirmed.get("status").asText()).isEqualTo("CONFIRMED");
		assertThat(confirmed.get("actionId").asText()).isEqualTo(actionId.toString());
		String label = confirmed.get("result").get("label").asText();
		assertThat(label).startsWith("PO-");

		Map<String, Object> order = admin.queryForMap("SELECT id, po_number, status, vendor_id, created_by FROM purchase_orders");
		assertThat(order).containsEntry("po_number", label).containsEntry("status", "DRAFT")
				.containsEntry("vendor_id", vendor).containsEntry("created_by", adminA);
		assertThat(confirmed.get("result").get("href").asText()).isEqualTo("/orders/" + order.get("id"));
		List<Map<String, Object>> lines = admin.queryForList(
				"SELECT ingredient_id, quantity, unit, expected_price FROM purchase_order_lines WHERE po_id = ? ORDER BY quantity DESC",
				order.get("id"));
		assertThat(lines).hasSize(2);
		assertThat(lines.get(0)).containsEntry("ingredient_id", rice).containsEntry("unit", "KG");
		assertThat((BigDecimal) lines.get(0).get("quantity")).isEqualByComparingTo("25");
		assertThat((BigDecimal) lines.get(0).get("expected_price")).isEqualByComparingTo("45");
		assertThat(lines.get(1)).containsEntry("ingredient_id", sugar).containsEntry("unit", "KG");
		assertThat((BigDecimal) lines.get(1).get("quantity")).isEqualByComparingTo("2.5");
		assertThat((BigDecimal) lines.get(1).get("expected_price")).isEqualByComparingTo("50");

		Map<String, Object> row = admin.queryForMap(
				"SELECT status, decided_by, result_label FROM assistant_pending_actions WHERE id = ?", actionId);
		assertThat(row).containsEntry("status", "CONFIRMED").containsEntry("decided_by", adminA).containsEntry("result_label", label);
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = 'ASSISTANT_ACTION_CONFIRMED' AND entity_id = ? AND actor_user_id = ?",
				Integer.class, actionId, adminA)).isEqualTo(1);

		HttpResponse<String> again = post(ADMIN_A, "/api/v1/assistant/actions/" + actionId + "/confirm");
		assertThat(again.statusCode()).as(again.body()).isEqualTo(200);
		assertThat(json.readTree(again.body())).isEqualTo(confirmed);
		assertThat(admin.queryForObject("SELECT count(*) FROM purchase_orders", Integer.class)).isEqualTo(1);
		assertThat(admin.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'ASSISTANT_ACTION_CONFIRMED'",
				Integer.class)).isEqualTo(1);
	}

	@Test
	@DisplayName("confirm: a payload changed after drafting is refused KMS-400195, the request or the card, and nothing is saved")
	void tamperedRefused() throws Exception {
		UUID actionId = (UUID) propose("balaji", null, null).get("draft_id");
		admin.update("""
				UPDATE assistant_pending_actions
				SET payload = jsonb_set(payload, '{request,lines,0,quantity}', '250')
				WHERE id = ?
				""", actionId);
		assertRefused(post(ADMIN_A, "/api/v1/assistant/actions/" + actionId + "/confirm"), 409, "KMS-400195");

		UUID second = newRunDraft();
		admin.update("""
				UPDATE assistant_pending_actions
				SET payload = jsonb_set(payload, '{card,title}', '"Purchase order for somebody else"')
				WHERE id = ?
				""", second);
		assertRefused(post(ADMIN_A, "/api/v1/assistant/actions/" + second + "/confirm"), 409, "KMS-400195");

		assertThat(admin.queryForObject("SELECT count(*) FROM purchase_orders", Integer.class)).isZero();
		assertThat(admin.queryForList("SELECT status FROM assistant_pending_actions", String.class))
				.containsOnly("PROPOSED");
	}

	@Test
	@DisplayName("confirm: a draft over 24 hours old is refused KMS-400194, and reads back EXPIRED")
	void expiredRefused() throws Exception {
		UUID actionId = (UUID) propose("balaji", null, null).get("draft_id");
		admin.update("""
				UPDATE assistant_pending_actions
				SET created_at = now() - interval '25 hours', expires_at = now() - interval '1 hour'
				WHERE id = ?
				""", actionId);

		assertRefused(post(ADMIN_A, "/api/v1/assistant/actions/" + actionId + "/confirm"), 409, "KMS-400194");
		assertRefused(post(ADMIN_A, "/api/v1/assistant/actions/" + actionId + "/reject"), 409, "KMS-400194");
		assertThat(admin.queryForObject("SELECT count(*) FROM purchase_orders", Integer.class)).isZero();

		// The reply the run saved carries the card, now EXPIRED.
		admin.update("""
				INSERT INTO assistant_messages (tenant_id, conversation_id, role, content, run_id)
				VALUES (?, ?, 'ASSISTANT', 'A draft is ready below.', ?)
				""", templeA, conversation, run);
		JsonNode detail = json.readTree(get(ADMIN_A, "/api/v1/assistant/conversations/" + conversation).body());
		JsonNode proposals = detail.get("messages").get(0).get("proposals");
		assertThat(proposals).hasSize(1);
		assertThat(proposals.get(0).get("actionId").asText()).isEqualTo(actionId.toString());
		assertThat(proposals.get(0).get("status").asText()).isEqualTo("EXPIRED");
		assertThat(proposals.get(0).get("lines")).hasSize(2);
	}

	@Test
	@DisplayName("reject, then confirm: refused KMS-400196; a second reject too; nothing ordered; the reject audited")
	void rejectThenConfirm() throws Exception {
		UUID actionId = (UUID) propose("balaji", null, null).get("draft_id");

		HttpResponse<String> rejected = post(ADMIN_A, "/api/v1/assistant/actions/" + actionId + "/reject");
		assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(200);
		assertThat(json.readTree(rejected.body()).get("status").asText()).isEqualTo("REJECTED");
		assertThat(json.readTree(rejected.body()).get("result").isNull()).isTrue();

		assertRefused(post(ADMIN_A, "/api/v1/assistant/actions/" + actionId + "/confirm"), 409, "KMS-400196");
		assertRefused(post(ADMIN_A, "/api/v1/assistant/actions/" + actionId + "/reject"), 409, "KMS-400196");
		assertThat(admin.queryForObject("SELECT count(*) FROM purchase_orders", Integer.class)).isZero();
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = 'ASSISTANT_ACTION_REJECTED' AND entity_id = ?",
				Integer.class, actionId)).isEqualTo(1);
	}

	@Test
	@DisplayName("another person at the temple, or another temple, gets 404 for confirm and reject; nothing changes")
	void notYourDraft() throws Exception {
		UUID actionId = (UUID) propose("balaji", null, null).get("draft_id");

		for (String uid : List.of(MANAGER_A, ADMIN_B)) {
			HttpResponse<String> confirm = post(uid, "/api/v1/assistant/actions/" + actionId + "/confirm");
			assertRefused(confirm, 404, "KMS-400030");
			assertThat(confirm.body()).doesNotContain("Balaji");
			assertRefused(post(uid, "/api/v1/assistant/actions/" + actionId + "/reject"), 404, "KMS-400030");
		}
		assertThat(admin.queryForObject("SELECT count(*) FROM purchase_orders", Integer.class)).isZero();
		assertThat(admin.queryForObject("SELECT status FROM assistant_pending_actions WHERE id = ?", String.class, actionId))
				.isEqualTo("PROPOSED");
	}

	@Test
	@DisplayName("a Volunteer is refused both endpoints (no CONFIRM_ASSISTANT_ACTION)")
	void volunteerRefused() throws Exception {
		UUID actionId = (UUID) propose("balaji", null, null).get("draft_id");
		assertThat(post(VOLUNTEER_A, "/api/v1/assistant/actions/" + actionId + "/confirm").statusCode()).isEqualTo(403);
		assertThat(post(VOLUNTEER_A, "/api/v1/assistant/actions/" + actionId + "/reject").statusCode()).isEqualTo(403);
		assertThat(admin.queryForObject("SELECT count(*) FROM purchase_orders", Integer.class)).isZero();
	}

	@Test
	@DisplayName("CONFIRM_ASSISTANT_ACTION alone cannot confirm an order: the order's own MANAGE_PURCHASE_ORDERS is required")
	void confirmNeedsTheThingsOwnPermission() {
		// No role holds CONFIRM_ASSISTANT_ACTION without MANAGE_PURCHASE_ORDERS today, so the check is
		// exercised on the service with exactly that authentication.
		UUID actionId = (UUID) propose("balaji", null, null).get("draft_id");
		SecurityContextHolder.clearContext();
		TenantContext.set(templeA);
		AuthenticatedUser person = new AuthenticatedUser(users.findById(adminA).orElseThrow());
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
				person, null, AuthorityUtils.createAuthorityList("USE_KITCHEN_ASSISTANT", "CONFIRM_ASSISTANT_ACTION")));

		assertThatThrownBy(() -> actions.confirm(person, actionId)).isInstanceOf(AccessDeniedException.class);
		assertThat(admin.queryForObject("SELECT count(*) FROM purchase_orders", Integer.class)).isZero();
		assertThat(admin.queryForObject("SELECT status FROM assistant_pending_actions WHERE id = ?", String.class, actionId))
				.isEqualTo("PROPOSED");
	}

	// ---------------------------------------------------------------------

	private Map<String, Object> propose(String vendorName, Object lines, String neededBy) {
		actAsAgent("agent:read", "agent:propose", "MANAGE_PURCHASE_ORDERS");
		try {
			return tools.proposePurchaseOrder(vendorName, lines, neededBy);
		} finally {
			TenantContext.clear();
			SecurityContextHolder.clearContext();
		}
	}

	/** The run's cards, read as the chat service reads them: under the temple, through RLS. */
	private List<AssistantProposal> cards() {
		TenantContext.set(templeA);
		try {
			return actions.forRun(run);
		} finally {
			TenantContext.clear();
		}
	}

	/** A second draft, from a second run (the first run finished), so its key differs. */
	private UUID newRunDraft() {
		admin.update("""
				UPDATE assistant_runs SET status = 'SUCCEEDED', finished_at = now(), duration_ms = 1 WHERE id = ?
				""", run);
		run = admin.queryForObject("""
				INSERT INTO assistant_runs (tenant_id, conversation_id, user_id, model) VALUES (?, ?, ?, 'sonnet')
				RETURNING id
				""", UUID.class, templeA, conversation, adminA);
		return (UUID) propose("balaji", null, null).get("draft_id");
	}

	private void actAsAgent(String... authorities) {
		TenantContext.set(templeA);
		AgentPrincipal agent = new AgentPrincipal(adminA, templeA, conversation, Set.of("agent:read", "agent:propose"));
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
				agent, null, AuthorityUtils.createAuthorityList(authorities)));
	}

	private void assertRefused(HttpResponse<String> response, int status, String code) throws Exception {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
		assertThat(json.readTree(response.body()).get("code").asText()).isEqualTo(code);
	}

	private HttpResponse<String> post(String uid, String path) throws Exception {
		return http.send(HttpRequest.newBuilder(uri(path))
				.header("Authorization", "Bearer " + uid)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.noBody())
				.build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> get(String uid, String path) throws Exception {
		return http.send(HttpRequest.newBuilder(uri(path))
				.header("Authorization", "Bearer " + uid)
				.GET().build(), HttpResponse.BodyHandlers.ofString());
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
	}

	private UUID tenant(String slug, String name) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class, slug, name);
	}

	private UUID user(UUID tenant, String uid, String role, String phone) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, ?, ?, 'ACTIVE')
				RETURNING id
				""", UUID.class, tenant, uid, uid + "@example.com", phone, role);
	}

	private UUID ingredient(String name, String unit) {
		UUID id = admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit) VALUES (?, ?, 'Grains', ?)
				RETURNING id
				""", UUID.class, templeA, name, unit);
		admin.update("INSERT INTO inventory_items (tenant_id, ingredient_id, reorder_threshold) VALUES (?, ?, 1)",
				templeA, id);
		return id;
	}

	private void supply(UUID ingredient, String price) {
		admin.update("""
				INSERT INTO vendor_supplies (tenant_id, vendor_id, ingredient_id, last_price, preferred)
				VALUES (?, ?, ?, ?::numeric, true)
				""", templeA, vendor, ingredient, price);
	}

	private void listLine(UUID ingredient, String quantity, String unit, boolean included) {
		admin.update("""
				INSERT INTO shopping_list_lines (id, tenant_id, ingredient_id, suggested_qty, unit, included, hand_added)
				VALUES (gen_random_uuid(), ?, ?, ?::numeric, ?, ?, true)
				""", templeA, ingredient, quantity, unit, included);
	}
}
