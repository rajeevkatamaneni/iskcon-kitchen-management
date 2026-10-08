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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.assistant.mcp.InventoryTools.InventoryResult;
import org.iskcon.kms.assistant.mcp.InventoryTools.InventoryRow;
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
 * The two Kitchen Assistant stock tools, called with the temple set directly on the thread (E10-T6).
 *
 * <p>The agent-token filter that sets the temple in real use is a separate task (T5), so here the
 * temple and the caller's authorities are put on the thread by the test, exactly where that filter
 * will put them. Everything is read through the application's unprivileged connection, so RLS is
 * what keeps temple B out of temple A's answer — not a {@code WHERE} clause in the tool.
 *
 * <p>Shares the suite's common context: no nested configuration, nothing mocked.
 */
class InventoryToolsIT extends AbstractIntegrationTest {

	@Autowired
	private InventoryTools tools;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private ConversationTools conversationTools;

	private JdbcTemplate admin;
	private McpStockFixture fixture;

	@BeforeEach
	void setUp() {
		TenantContext.clear();
		SecurityContextHolder.clearContext();
		admin = new JdbcTemplate(adminDataSource());
		fixture = new McpStockFixture(admin);
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		SecurityContextHolder.clearContext();
		McpStockFixture.tearDown(admin);
	}

	// ---- Each temple sees only its own stock -----------------------------

	@Test
	@DisplayName("get_inventory for one item returns only the calling temple's row, with its own Kannada name")
	void getInventoryIsTenantScoped() {
		actAs(fixture.templeA, "MANAGE_INVENTORY");
		InventoryResult a = tools.getInventory("dal");

		assertThat(a.items()).extracting(InventoryRow::name).containsExactly("Toor Dal");
		InventoryRow dalA = a.items().get(0);
		assertThat(dalA.onHand()).isEqualByComparingTo("2");
		assertThat(dalA.unit()).isEqualTo("Kg");
		assertThat(dalA.reorderLevel()).isEqualByComparingTo("5");
		assertThat(dalA.low()).isTrue();
		assertThat(dalA.nameKn()).isEqualTo(McpStockFixture.DAL_KN_A);
		assertThat(dalA.cover().known()).as("no consumption history, so no estimate").isFalse();

		actAs(fixture.templeB, "MANAGE_INVENTORY");
		InventoryResult b = tools.getInventory("dal");

		assertThat(b.items()).extracting(InventoryRow::name).containsExactly("Toor Dal");
		assertThat(b.items().get(0).onHand()).isEqualByComparingTo("100");
		assertThat(b.items().get(0).low()).isFalse();
		assertThat(b.items().get(0).nameKn()).isEqualTo(McpStockFixture.DAL_KN_B);
	}

	@Test
	@DisplayName("get_inventory with no name lists only the calling temple's items")
	void getInventoryListsOnlyOwnTemple() {
		actAs(fixture.templeB, "MANAGE_INVENTORY");
		InventoryResult b = tools.getInventory(null);

		assertThat(b.items()).extracting(InventoryRow::name).containsExactly("Basmati Rice", "Toor Dal");
		assertThat(b.totalMatching()).isEqualTo(2);
		assertThat(b.truncated()).isFalse();
	}

	@Test
	@DisplayName("list_low_stock returns only the calling temple's low items")
	void listLowStockIsTenantScoped() {
		actAs(fixture.templeB, "MANAGE_INVENTORY");
		InventoryResult b = tools.listLowStock();

		assertThat(b.items()).extracting(InventoryRow::name).containsExactly("Basmati Rice");
		assertThat(b.items()).allMatch(InventoryRow::low);
		assertThat(b.items().get(0).nameKn()).as("temple B has no glossary entry for rice").isNull();
	}

	// ---- Matching ---------------------------------------------------------

	@Test
	@DisplayName("get_inventory finds an item by its Kannada name, ignoring case in English")
	void getInventoryMatchesKannadaAndCase() {
		actAs(fixture.templeA, "MANAGE_INVENTORY");

		assertThat(tools.getInventory(McpStockFixture.GHEE_KN_A).items())
				.extracting(InventoryRow::name).containsExactly("Ghee");
		assertThat(tools.getInventory("GHEE").items())
				.extracting(InventoryRow::name).containsExactly("Ghee");
		assertThat(tools.getInventory("cardamom").items()).isEmpty();
	}

	// ---- The 50-row cap ---------------------------------------------------

	@Test
	@DisplayName("both tools stop at 50 rows and say how many there were")
	void resultsAreCappedAtFifty() {
		actAs(fixture.templeA, "MANAGE_INVENTORY");

		InventoryResult all = tools.getInventory(null);
		assertThat(all.items()).hasSize(InventoryTools.MAX_ROWS);
		assertThat(all.returned()).isEqualTo(50);
		assertThat(all.totalMatching()).isEqualTo(McpStockFixture.FILLERS_A + 2);
		assertThat(all.truncated()).isTrue();

		InventoryResult low = tools.listLowStock();
		assertThat(low.items()).hasSize(50);
		assertThat(low.totalMatching()).as("53 fillers and Toor Dal; Ghee is fine").isEqualTo(54);
		assertThat(low.truncated()).isTrue();
	}

	// ---- The caller's own permission still applies -----------------------

	@Test
	@DisplayName("a caller without MANAGE_INVENTORY cannot read stock through the assistant")
	void permissionIsEnforced() {
		actAs(fixture.templeA, "agent:read", "VIEW_OWN_SHIFTS");

		assertThatThrownBy(() -> tools.getInventory(null)).isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> tools.listLowStock()).isInstanceOf(AccessDeniedException.class);

		// And through the registered handler, the model is told in words rather than handed a stack.
		CallToolResult result = call(captureAs(fixture.templeA, "agent:read"),
				McpServerConfiguration.LIST_LOW_STOCK, Map.of());
		assertThat(result.isError()).isTrue();
		assertThat(text(result)).isEqualTo(McpServerConfiguration.NOT_ALLOWED_MESSAGE);
	}

	// ---- The thread hop (AgentCallScope) ---------------------------------

	@Test
	@DisplayName("a handler run on another thread reads the temple captured on the request thread, and leaves that thread clean")
	void handlerSeesCapturedTempleOnAnotherThread() throws Exception {
		McpTransportContext captured = captureAs(fixture.templeA, "agent:read", "MANAGE_INVENTORY");

		// What the SDK does by default: run the handler on a different thread from the one the
		// request arrived on. A single-thread pool, so the "afterwards" check below inspects the very
		// thread the handler ran on.
		ExecutorService worker = Executors.newSingleThreadExecutor();
		try {
			CallToolResult result = worker.submit(() ->
					call(captured, McpServerConfiguration.GET_INVENTORY, Map.of("item_name", "dal"))).get();

			assertThat(result.isError()).as(text(result)).isFalse();
			JsonNode payload = objectMapper.readTree(text(result));
			assertThat(payload.get("items")).hasSize(1);
			assertThat(payload.get("items").get(0).get("name").asText()).isEqualTo("Toor Dal");
			assertThat(payload.get("items").get(0).get("on_hand").decimalValue()).isEqualByComparingTo("2");
			assertThat(payload.get("items").get(0).get("name_kn").asText()).isEqualTo(McpStockFixture.DAL_KN_A);
			assertThat(payload.get("items").get(0).get("cover").get("known").asBoolean()).isFalse();

			AtomicReference<Optional<UUID>> leftOver = new AtomicReference<>();
			worker.submit(() -> leftOver.set(TenantContext.get())).get();
			assertThat(leftOver.get()).as("a pooled worker must not keep the temple").isEmpty();
		} finally {
			worker.shutdownNow();
		}
	}

	@Test
	@DisplayName("a call that carries no temple is refused in words, not answered with an empty stock list")
	void noTempleIsRefused() {
		CallToolResult result = call(McpTransportContext.EMPTY, McpServerConfiguration.GET_INVENTORY, Map.of());

		assertThat(result.isError()).isTrue();
		assertThat(text(result)).isEqualTo(McpServerConfiguration.NO_TEMPLE_MESSAGE);
	}

	// ---------------------------------------------------------------------

	private void actAs(UUID tenant, String... authorities) {
		TenantContext.set(tenant);
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
				"agent", null, AuthorityUtils.createAuthorityList(authorities)));
	}

	/** What the transport's extractor would capture on a request thread the filter had set up. */
	private McpTransportContext captureAs(UUID tenant, String... authorities) {
		actAs(tenant, authorities);
		McpTransportContext captured = AgentCallScope.captureCurrent();
		TenantContext.clear();
		SecurityContextHolder.clearContext();
		return captured;
	}

	private CallToolResult call(McpTransportContext context, String toolName, Map<String, Object> arguments) {
		List<SyncToolSpecification> specs = McpServerConfiguration.toolSpecifications(tools, conversationTools, objectMapper);
		SyncToolSpecification spec = specs.stream()
				.filter(s -> s.tool().name().equals(toolName))
				.findFirst()
				.orElseThrow();
		return spec.callHandler().apply(context, new CallToolRequest(toolName, arguments));
	}

	private static String text(CallToolResult result) {
		return ((TextContent) result.content().get(0)).text();
	}
}
