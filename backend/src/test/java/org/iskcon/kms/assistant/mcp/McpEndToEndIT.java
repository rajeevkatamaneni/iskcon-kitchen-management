package org.iskcon.kms.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.Permission;
import org.iskcon.kms.auth.RolePermissions;
import org.iskcon.kms.assistant.token.AgentTokenFilterIT;
import org.iskcon.kms.assistant.token.AgentTokenService;
import org.iskcon.kms.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * The Kitchen Assistant's MCP server end to end: real agent tokens minted by
 * {@link AgentTokenService}, through the real {@code /mcp/**} security chain, into the real SDK
 * transport and the real tools, against a real database under RLS (E10-T7).
 *
 * <p>This replaces T6's {@code McpTransportIT}, which had to run behind a test-only stand-in for the
 * security chain because the agent-token filter (T5) was being built at the same time. Every check
 * that class made is made here, now with a token the filter has to accept, so the stand-in and the
 * context it cost are gone.
 *
 * <p><strong>Why the {@code @Import} and the property.</strong> Not because this class needs the echo
 * endpoint: it is so this class's context configuration is byte-for-byte that of
 * {@link AgentTokenFilterIT}, and Spring's context cache hands both classes the same application.
 * The fixed key is what lets {@link AgentTokenFilterIT#expiredReadToken} sign an already-expired
 * token this server will verify the signature of — with the in-memory default the key is unknowable
 * from outside the bean. Sharing that context rather than the suite's common one costs nothing: it
 * exists anyway, for the filter's own test. Keep the two classes' annotations identical.
 *
 * <p><strong>How a refusal reaches the model.</strong> Two different layers refuse, and they look
 * different on purpose. The <em>chain</em> refuses a bad token (missing, expired, a disabled person)
 * with a bare HTTP 401 before any MCP processing — {@code claude -p} sees a failed connection, not a
 * tool result. The <em>tool</em> refuses a valid token whose person lacks {@code MANAGE_INVENTORY}:
 * the HTTP status and the JSON-RPC call both succeed, and the tool result carries
 * {@code isError: true} with a plain sentence the model can relay. That is MCP's convention for an
 * error the model should see and reason about rather than a protocol failure.
 */
@Import(AgentTokenFilterIT.EchoEndpoint.class)
@TestPropertySource(properties = "kms.assistant.token.key=" + AgentTokenFilterIT.KEY_BASE64)
class McpEndToEndIT extends AbstractIntegrationTest {

	@LocalServerPort
	private int port;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private AgentTokenService tokens;

	private final HttpClient http = HttpClient.newHttpClient();
	private JdbcTemplate admin;
	private McpStockFixture fixture;
	private UUID adminA;
	private UUID adminB;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		fixture = new McpStockFixture(admin);
		adminA = userId("uid-mcp-a");
		adminB = userId("uid-mcp-b");
	}

	@AfterEach
	void tearDown() {
		McpStockFixture.tearDown(admin);
	}

	@Test
	@DisplayName("initialize, tools/list and tools/call over HTTP with a real agent token, each temple seeing only its own stock")
	void eachTempleSeesOnlyItsOwnStock() throws Exception {
		String tokenA = readToken(adminA, fixture.templeA);
		String tokenB = readToken(adminB, fixture.templeB);

		HttpResponse<String> init = post(tokenA, """
				{"jsonrpc":"2.0","id":1,"method":"initialize","params":{
				  "protocolVersion":"2025-06-18","capabilities":{},
				  "clientInfo":{"name":"mcp-end-to-end-it","version":"1"}}}""");
		assertThat(init.statusCode()).as(init.body()).isEqualTo(200);
		JsonNode initResult = objectMapper.readTree(init.body()).get("result");
		assertThat(initResult.get("serverInfo").get("name").asText()).isEqualTo("ikms");
		assertThat(initResult.get("capabilities").has("tools")).isTrue();

		assertThat(post(tokenA, """
				{"jsonrpc":"2.0","method":"notifications/initialized"}""").statusCode()).isEqualTo(202);

		HttpResponse<String> list = post(tokenA, """
				{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""");
		assertThat(list.statusCode()).as(list.body()).isEqualTo(200);
		JsonNode tools = objectMapper.readTree(list.body()).get("result").get("tools");
		assertThat(tools.findValuesAsText("name")).containsExactlyInAnyOrder(
				"get_inventory", "list_low_stock", "set_conversation_language");
		for (JsonNode tool : tools) {
			assertThat(tool.get("inputSchema").path("properties").has("tenant_id"))
					.as("no tool takes a tenant").isFalse();
		}

		// get_inventory: both temples hold a "Toor Dal". Each token gets one row, its own figure and
		// its own Kannada — a leak would show as two rows, or the other temple's 100 / 2.
		JsonNode dalA = toolPayload(callTool(tokenA, 3, "get_inventory", "{\"item_name\":\"dal\"}"));
		assertThat(dalA.get("items")).hasSize(1);
		assertThat(dalA.get("items").get(0).get("on_hand").decimalValue()).isEqualByComparingTo("2");
		assertThat(dalA.get("items").get(0).get("name_kn").asText()).isEqualTo(McpStockFixture.DAL_KN_A);

		JsonNode dalB = toolPayload(callTool(tokenB, 4, "get_inventory", "{\"item_name\":\"dal\"}"));
		assertThat(dalB.get("items")).hasSize(1);
		assertThat(dalB.get("items").get(0).get("on_hand").decimalValue()).isEqualByComparingTo("100");
		assertThat(dalB.get("items").get(0).get("name_kn").asText()).isEqualTo(McpStockFixture.DAL_KN_B);

		// get_inventory with no name: B's whole stock is its two items, none of A's 55.
		JsonNode allB = toolPayload(callTool(tokenB, 5, "get_inventory", "{}"));
		assertThat(allB.get("items").findValuesAsText("name"))
				.containsExactlyInAnyOrder("Toor Dal", "Basmati Rice");

		// list_low_stock: B's only low item is its rice. A has 54 low items, cut at 50, and B's rice
		// is not among them.
		JsonNode lowB = toolPayload(callTool(tokenB, 6, "list_low_stock", "{}"));
		assertThat(lowB.get("items").findValuesAsText("name")).containsExactly("Basmati Rice");

		JsonNode lowA = toolPayload(callTool(tokenA, 7, "list_low_stock", "{}"));
		assertThat(lowA.get("returned").asInt()).isEqualTo(50);
		assertThat(lowA.get("truncated").asBoolean()).isTrue();
		assertThat(lowA.get("items").findValuesAsText("name")).doesNotContain("Basmati Rice");
	}

	@Test
	@DisplayName("no token: 401 at /mcp, before any MCP processing")
	void noToken() throws Exception {
		HttpResponse<String> response = post(null, """
				{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""");
		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(response.body()).as("a plain 401, no detail").isEmpty();
	}

	@Test
	@DisplayName("an expired agent token: 401 at /mcp")
	void expiredToken() throws Exception {
		String expired = AgentTokenFilterIT.expiredReadToken(adminA, fixture.templeA);

		assertThat(callTool(expired, 1, "list_low_stock", "{}").statusCode()).isEqualTo(401);
	}

	@Test
	@DisplayName("a token for a disabled person: 401 at /mcp, from the next call on")
	void disabledUser() throws Exception {
		String token = readToken(adminA, fixture.templeA);
		assertThat(callTool(token, 1, "list_low_stock", "{}").statusCode())
				.as("the same token works while the person is active").isEqualTo(200);

		admin.update("UPDATE users SET status = 'DISABLED' WHERE id = ?", adminA);

		assertThat(callTool(token, 2, "list_low_stock", "{}").statusCode()).isEqualTo(401);
	}

	@Test
	@DisplayName("a valid token for a person without MANAGE_INVENTORY: the call succeeds, the tool refuses in words")
	void personWithoutStockPermission() throws Exception {
		// Picked by asking RolePermissions rather than assumed, so a policy change that gave
		// volunteers the stock screen makes this test say so instead of passing for the wrong reason.
		assertThat(RolePermissions.forRole(User.Role.VOLUNTEER)).doesNotContain(Permission.MANAGE_INVENTORY);
		UUID volunteer = admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-mcp-volunteer', 'Test Volunteer', 'mcp-vol@example.com', '+919876500092',
				        'VOLUNTEER', 'ACTIVE')
				RETURNING id
				""", UUID.class, fixture.templeA);
		String token = readToken(volunteer, fixture.templeA);

		for (String tool : new String[] {"get_inventory", "list_low_stock"}) {
			HttpResponse<String> response = callTool(token, 1, tool, "{}");
			assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
			JsonNode envelope = objectMapper.readTree(response.body());
			assertThat(envelope.has("error")).as("not a JSON-RPC error: the model sees a tool result").isFalse();
			JsonNode result = envelope.get("result");
			assertThat(result.get("isError").asBoolean()).as(tool).isTrue();
			String text = result.get("content").get(0).get("text").asText();
			assertThat(text).isEqualTo(McpServerConfiguration.NOT_ALLOWED_MESSAGE);
			assertThat(text).as("no stock figure leaks into the refusal").doesNotContain("Toor Dal");
		}
	}

	@Test
	@DisplayName("a token without agent:read: 403 at the endpoint")
	void tokenWithoutReadScope() throws Exception {
		String proposeOnly = tokens.mint(adminA, fixture.templeA, UUID.randomUUID(),
				Set.of(AgentTokenService.SCOPE_PROPOSE));

		assertThat(callTool(proposeOnly, 1, "list_low_stock", "{}").statusCode()).isEqualTo(403);
	}

	@Test
	@DisplayName("GET /mcp is 405: a stateless server offers no server-to-client stream")
	void getIsMethodNotAllowed() throws Exception {
		HttpResponse<String> get = http.send(HttpRequest.newBuilder(uri())
				.header("Authorization", "Bearer " + readToken(adminA, fixture.templeA))
				.header("Accept", "text/event-stream")
				.GET().build(), HttpResponse.BodyHandlers.ofString());
		assertThat(get.statusCode()).isEqualTo(405);
	}

	// ---------------------------------------------------------------------

	private String readToken(UUID userId, UUID tenant) {
		return tokens.mint(userId, tenant, UUID.randomUUID(), Set.of(AgentTokenService.SCOPE_READ));
	}

	private UUID userId(String firebaseUid) {
		return admin.queryForObject("SELECT id FROM users WHERE firebase_uid = ?", UUID.class, firebaseUid);
	}

	private HttpResponse<String> callTool(String token, int id, String name, String arguments) throws Exception {
		return post(token, """
				{"jsonrpc":"2.0","id":%d,"method":"tools/call","params":{"name":"%s","arguments":%s}}"""
				.formatted(id, name, arguments));
	}

	/** The tool's own JSON, out of the JSON-RPC envelope, after checking the call succeeded. */
	private JsonNode toolPayload(HttpResponse<String> response) throws Exception {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		JsonNode result = objectMapper.readTree(response.body()).get("result");
		assertThat(result.get("isError").asBoolean()).as(response.body()).isFalse();
		return objectMapper.readTree(result.get("content").get(0).get("text").asText());
	}

	private HttpResponse<String> post(String token, String body) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(uri())
				// Streamable HTTP requires the client to accept both; the transport refuses otherwise.
				.header("Accept", "application/json, text/event-stream")
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body));
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	private URI uri() {
		return URI.create("http://localhost:" + port + "/mcp");
	}
}
