package org.iskcon.kms.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.assistant.action.PendingActionService;
import org.iskcon.kms.assistant.api.AssistantBudget;
import org.iskcon.kms.assistant.api.AssistantConversationStore;
import org.iskcon.kms.assistant.mcp.InventoryTools.InventoryResult;
import org.iskcon.kms.assistant.mcp.InventoryTools.InventoryRow;
import org.iskcon.kms.assistant.names.ConversationNames;
import org.iskcon.kms.assistant.token.AgentPrincipal;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.inventory.InventoryItemService;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.translation.GlossaryService;
import org.iskcon.kms.translation.StockNameFillService;
import org.iskcon.kms.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Item names in the conversation's language (E10-NAMES-B): Rajeev asked for Telugu and got English
 * item names, because the tools only ever carried Kannada.
 *
 * <p>The tools, the language tool and the conversation store are built here by hand around the real
 * services and a real database, with one stand-in: the name fill. It is a Mockito mock, so the tests
 * can see exactly when it is asked for, and so that no test ever starts a real fill (which would call
 * a runner, and on a developer's Mac the default runner is their own, paid one). Built by hand rather
 * than with {@code @MockBean}, which would add a context to the suite's cache (memory note on the
 * context cache explosion).
 */
class ConversationLanguageNamesIT extends AbstractIntegrationTest {

	private static final String DAL_TE = "కందిపప్పు";

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private GlossaryService glossary;

	@Autowired
	private InventoryItemService inventory;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private AssistantBudget budget;

	@Autowired
	private AuditService audit;

	@Autowired
	private PendingActionService pending;

	@Autowired
	private UserRepository users;

	private JdbcTemplate admin;
	private McpStockFixture fixture;
	private StockNameFillService fill;
	private ConversationNames names;
	private InventoryTools tools;

	@BeforeEach
	void setUp() {
		TenantContext.clear();
		SecurityContextHolder.clearContext();
		admin = new JdbcTemplate(adminDataSource());
		fixture = new McpStockFixture(admin);
		// Temple A: Kannada for Toor Dal and Ghee (the fixture's), Telugu for Toor Dal only.
		fixture.name(fixture.templeA, "te", "Toor Dal", DAL_TE);
		fill = mock(StockNameFillService.class);
		names = new ConversationNames(jdbc, glossary, fill);
		tools = new InventoryTools(inventory, names);
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		SecurityContextHolder.clearContext();
		McpStockFixture.tearDown(admin);
	}

	@Test
	@DisplayName("a Telugu conversation gets the stored Telugu name, never the Kannada one; an item with no Telugu name is pending and the fill is asked for")
	void teluguNamesAndPending() throws Exception {
		actAs(fixture.conversation(fixture.templeA, fixture.userA, "te"));

		InventoryResult dal = tools.getInventory("dal");
		assertThat(dal.items()).extracting(InventoryRow::nameLocal, InventoryRow::namePending)
				.containsExactly(org.assertj.core.groups.Tuple.tuple(DAL_TE, null));

		InventoryResult ghee = tools.getInventory("ghee");
		assertThat(ghee.items()).extracting(InventoryRow::nameLocal, InventoryRow::namePending)
				.as("ghee has a Kannada name but no Telugu one")
				.containsExactly(org.assertj.core.groups.Tuple.tuple(null, true));
		JsonNode wire = objectMapper.valueToTree(ghee).get("items").get(0);
		assertThat(wire.has("name_local")).isFalse();
		assertThat(wire.get("name_pending").asBoolean()).isTrue();
		assertThat(wire.has("name_kn")).as("the Kannada-only field is gone").isFalse();

		// Once per call that met an unnamed item (both calls read the whole list, fillers included);
		// the service dedupes, so asking again costs a count query, not a fill.
		verify(fill, atLeastOnce()).ensureNames(fixture.templeA, "te");

		// Matching works on the stored Telugu name.
		assertThat(tools.getInventory(DAL_TE).items()).extracting(InventoryRow::name).containsExactly("Toor Dal");

		// list_low_stock the same way. Its first 50 (sorted by name) are the unnamed fillers, all pending;
		// Toor Dal, the 54th, is past the cap, so give a filler a Telugu name to see a named low row.
		fixture.name(fixture.templeA, "te", "Spice 01", "మసాలా 01");
		InventoryResult low = tools.listLowStock();
		assertThat(low.items().get(0)).extracting(InventoryRow::name, InventoryRow::nameLocal, InventoryRow::namePending)
				.containsExactly("Spice 01", "మసాలా 01", null);
		assertThat(low.items().subList(1, low.items().size()))
				.allMatch(r -> r.nameLocal() == null && Boolean.TRUE.equals(r.namePending()));
	}

	@Test
	@DisplayName("when every item asked about is named, nothing is pending and the fill is not asked for")
	void allNamedAsksNothing() {
		// Temple B holds two items; give its rice a Kannada name too, so both are named.
		fixture.name(fixture.templeB, "kn", "Basmati Rice", "ಬಾಸ್ಮತಿ ಅಕ್ಕಿ");
		actAs(fixture.conversation(fixture.templeB, fixture.userB, "kn"));

		InventoryResult b = tools.getInventory(null);
		assertThat(b.items()).extracting(InventoryRow::nameLocal)
				.containsExactly("ಬಾಸ್ಮತಿ ಅಕ್ಕಿ", McpStockFixture.DAL_KN_B);
		assertThat(b.items()).extracting(InventoryRow::namePending).containsOnlyNulls();
		tools.listLowStock();
		verify(fill, never()).ensureNames(any(), anyString());
	}

	@Test
	@DisplayName("an English conversation carries no local name and nothing pending, and asks for no fill")
	void englishHasNone() throws Exception {
		actAs(fixture.conversation(fixture.templeA, fixture.userA, "en"));

		InventoryResult all = tools.getInventory(null);
		assertThat(all.items()).allMatch(r -> r.nameLocal() == null && r.namePending() == null);
		JsonNode wire = objectMapper.valueToTree(all).get("items").get(0);
		assertThat(wire.has("name_local")).isFalse();
		assertThat(wire.has("name_pending")).isFalse();
		tools.listLowStock();
		verify(fill, never()).ensureNames(any(), anyString());
	}

	@Test
	@DisplayName("a token naming no conversation of this person's is treated as English, never another conversation's language")
	void unknownConversationIsEnglish() {
		UUID colleagues = fixture.conversation(fixture.templeA, fixture.userA, "kn");
		UUID stranger = admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-mcp-a2', 'Other', 'mcp-a2@example.com', '+919876500092', 'KITCHEN_MANAGER', 'ACTIVE')
				RETURNING id
				""", UUID.class, fixture.templeA);
		actAs(stranger, colleagues);

		assertThat(tools.getInventory("dal").items()).extracting(InventoryRow::nameLocal).containsOnlyNulls();
		verify(fill, never()).ensureNames(any(), anyString());
	}

	@Test
	@DisplayName("switching the conversation to Telugu asks for the Telugu names; switching to English asks for nothing")
	void switchingAsksForNames() {
		UUID conversation = fixture.conversation(fixture.templeA, fixture.userA, "kn");
		actAs(conversation);
		ConversationTools languageTool = new ConversationTools(jdbc, names);

		languageTool.setConversationLanguage("te");
		verify(fill).ensureNames(fixture.templeA, "te");

		languageTool.setConversationLanguage("en");
		verify(fill, never()).ensureNames(fixture.templeA, "en");

		// And the next tool call is in the new language: English, so no local names.
		assertThat(tools.getInventory("dal").items()).extracting(InventoryRow::nameLocal).containsOnlyNulls();
	}

	@Test
	@DisplayName("a conversation started in Telugu asks for the Telugu names; one started in English does not")
	void creatingAsksForNames() {
		AssistantConversationStore store = new AssistantConversationStore(jdbc, budget, audit, pending, names, new org.iskcon.kms.tenancy.TempleClock(jdbc));
		admin.update("UPDATE users SET preferred_language = 'te' WHERE id = ?", fixture.userA);
		TenantContext.set(fixture.templeA);

		AuthenticatedUser person = new AuthenticatedUser(users.findById(fixture.userA).orElseThrow());
		assertThat(store.create(person).language()).isEqualTo("te");
		verify(fill).ensureNames(fixture.templeA, "te");

		admin.update("UPDATE users SET preferred_language = 'en' WHERE id = ?", fixture.userA);
		assertThat(store.create(person).language()).isEqualTo("en");
		verify(fill, never()).ensureNames(fixture.templeA, "en");
	}

	@Test
	@DisplayName("a fill that cannot start never fails the tool")
	void fillFailureDoesNotFailTheTool() {
		org.mockito.Mockito.when(fill.ensureNames(any(), anyString())).thenThrow(new IllegalStateException("boom"));
		actAs(fixture.conversation(fixture.templeA, fixture.userA, "te"));

		assertThat(tools.getInventory("ghee").items()).extracting(InventoryRow::namePending).containsExactly(true);
	}

	// ---------------------------------------------------------------------

	private void actAs(UUID conversation) {
		UUID tenant = admin.queryForObject("SELECT tenant_id FROM assistant_conversations WHERE id = ?", UUID.class,
				conversation);
		UUID user = admin.queryForObject("SELECT user_id FROM assistant_conversations WHERE id = ?", UUID.class,
				conversation);
		actAs(user, conversation, tenant);
	}

	private void actAs(UUID user, UUID conversation) {
		actAs(user, conversation, fixture.templeA);
	}

	private void actAs(UUID user, UUID conversation, UUID tenant) {
		TenantContext.set(tenant);
		AgentPrincipal agent = new AgentPrincipal(user, tenant, conversation, Set.of("agent:read"));
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
				agent, null, AuthorityUtils.createAuthorityList("MANAGE_INVENTORY")));
	}
}
