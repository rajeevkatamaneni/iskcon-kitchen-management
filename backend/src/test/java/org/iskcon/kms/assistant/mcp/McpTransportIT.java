package org.iskcon.kms.assistant.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The MCP server over real HTTP: {@code initialize}, {@code tools/list} and {@code tools/call} at
 * {@code /mcp}, on the real embedded server and a random port (E10-T6).
 *
 * <p><strong>Why this class has a nested configuration, against the base class's advice.</strong>
 * The security chain that will guard {@code /mcp} — the agent-token filter — belongs to a task being
 * built in parallel (T5), and this task is not allowed to touch it. So {@link StandInAgentChain}
 * stands in for it: a filter chain on {@code /mcp/**}, ahead of the Firebase chain, that sets the
 * temple and the caller's authorities on the request thread exactly where T5's filter will, from
 * fields this test sets. It is test-only and costs one extra application context. Once T5 lands,
 * a test of the real token through this endpoint is T7's job.
 *
 * <p>What this proves that the handler tests cannot: that the SDK's transport, mounted through
 * Spring MVC, answers the three calls a client makes; and that the temple set on the
 * <em>request</em> thread reaches the tool, which the SDK runs on a Reactor worker. If that hop
 * were not bridged, the tool would refuse (no temple) and the assertions on temple A's figures
 * would fail.
 */
class McpTransportIT extends AbstractIntegrationTest {

	/** Set by each test; read by the stand-in filter on the request thread. */
	private static volatile UUID callerTenant;
	private static volatile List<String> callerAuthorities = List.of();

	@LocalServerPort
	private int port;

	@Autowired
	private ObjectMapper objectMapper;

	private final HttpClient http = HttpClient.newHttpClient();
	private JdbcTemplate admin;
	private McpStockFixture fixture;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		fixture = new McpStockFixture(admin);
	}

	@AfterEach
	void tearDown() {
		callerTenant = null;
		callerAuthorities = List.of();
		McpStockFixture.tearDown(admin);
	}

	@Test
	@DisplayName("initialize, tools/list and tools/call work over HTTP at /mcp, scoped to the caller's temple")
	void initializeListAndCall() throws Exception {
		callAs(fixture.templeA, "agent:read", "MANAGE_INVENTORY");

		HttpResponse<String> init = post("""
				{"jsonrpc":"2.0","id":1,"method":"initialize","params":{
				  "protocolVersion":"2025-06-18","capabilities":{},
				  "clientInfo":{"name":"mcp-transport-it","version":"1"}}}""");
		assertThat(init.statusCode()).as(init.body()).isEqualTo(200);
		JsonNode initResult = objectMapper.readTree(init.body()).get("result");
		assertThat(initResult.get("serverInfo").get("name").asText()).isEqualTo("ikms");
		assertThat(initResult.get("capabilities").has("tools")).isTrue();

		HttpResponse<String> initialized = post("""
				{"jsonrpc":"2.0","method":"notifications/initialized"}""");
		assertThat(initialized.statusCode()).isEqualTo(202);

		HttpResponse<String> list = post("""
				{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""");
		assertThat(list.statusCode()).as(list.body()).isEqualTo(200);
		JsonNode tools = objectMapper.readTree(list.body()).get("result").get("tools");
		assertThat(tools.findValuesAsText("name")).containsExactlyInAnyOrder("get_inventory", "list_low_stock");
		JsonNode getInventory = tools.get(0).get("name").asText().equals("get_inventory") ? tools.get(0) : tools.get(1);
		assertThat(getInventory.get("inputSchema").get("properties").has("item_name")).isTrue();
		assertThat(getInventory.get("inputSchema").get("properties").has("tenant_id"))
				.as("no tool takes a tenant").isFalse();

		JsonNode dalA = callGetInventory(3, "dal");
		assertThat(dalA.get("items")).hasSize(1);
		assertThat(dalA.get("items").get(0).get("on_hand").decimalValue()).isEqualByComparingTo("2");
		assertThat(dalA.get("items").get(0).get("name_kn").asText()).isEqualTo(McpStockFixture.DAL_KN_A);

		// The same call from temple B gets temple B's dal, and only it.
		callAs(fixture.templeB, "agent:read", "MANAGE_INVENTORY");
		JsonNode dalB = callGetInventory(4, "dal");
		assertThat(dalB.get("items")).hasSize(1);
		assertThat(dalB.get("items").get(0).get("on_hand").decimalValue()).isEqualByComparingTo("100");
		assertThat(dalB.get("items").get(0).get("name_kn").asText()).isEqualTo(McpStockFixture.DAL_KN_B);

		HttpResponse<String> low = post("""
				{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"list_low_stock","arguments":{}}}""");
		JsonNode lowB = toolPayload(low);
		assertThat(lowB.get("items").findValuesAsText("name")).containsExactly("Basmati Rice");
	}

	@Test
	@DisplayName("a caller without agent:read is refused at the endpoint")
	void endpointRequiresAgentRead() throws Exception {
		callAs(fixture.templeA, "MANAGE_INVENTORY");

		HttpResponse<String> init = post("""
				{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""");
		assertThat(init.statusCode()).isEqualTo(403);
	}

	@Test
	@DisplayName("GET /mcp is 405: a stateless server offers no server-to-client stream")
	void getIsMethodNotAllowed() throws Exception {
		callAs(fixture.templeA, "agent:read", "MANAGE_INVENTORY");

		HttpResponse<String> get = http.send(HttpRequest.newBuilder(uri())
				.header("Accept", "text/event-stream")
				.GET().build(), HttpResponse.BodyHandlers.ofString());
		assertThat(get.statusCode()).isEqualTo(405);
	}

	// ---------------------------------------------------------------------

	private static void callAs(UUID tenant, String... authorities) {
		callerTenant = tenant;
		callerAuthorities = List.of(authorities);
	}

	private JsonNode callGetInventory(int id, String itemName) throws Exception {
		HttpResponse<String> response = post("""
				{"jsonrpc":"2.0","id":%d,"method":"tools/call",
				 "params":{"name":"get_inventory","arguments":{"item_name":"%s"}}}""".formatted(id, itemName));
		return toolPayload(response);
	}

	/** The tool's own JSON, out of the JSON-RPC envelope, after checking the call succeeded. */
	private JsonNode toolPayload(HttpResponse<String> response) throws Exception {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		JsonNode result = objectMapper.readTree(response.body()).get("result");
		assertThat(result.get("isError").asBoolean()).as(response.body()).isFalse();
		return objectMapper.readTree(result.get("content").get(0).get("text").asText());
	}

	private HttpResponse<String> post(String body) throws Exception {
		return http.send(HttpRequest.newBuilder(uri())
				// Streamable HTTP requires the client to accept both; the transport refuses otherwise.
				.header("Accept", "application/json, text/event-stream")
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build(), HttpResponse.BodyHandlers.ofString());
	}

	private URI uri() {
		return URI.create("http://localhost:" + port + "/mcp");
	}

	/**
	 * Stands in for T5's agent-token chain. See the class comment. Ordered first so it, not the
	 * Firebase chain, owns {@code /mcp/**}; once T5's chain exists both match, and this one still
	 * wins in this test's context only.
	 */
	@TestConfiguration
	static class StandInAgentChain {

		@Bean
		@Order(Ordered.HIGHEST_PRECEDENCE)
		SecurityFilterChain standInAgentChain(HttpSecurity http) throws Exception {
			return http
					.securityMatcher(new AntPathRequestMatcher("/mcp/**"))
					.csrf(AbstractHttpConfigurer::disable)
					.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
					.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
					.addFilterBefore(new StandInAgentFilter(), AuthorizationFilter.class)
					.build();
		}
	}

	/** Sets the temple and authorities on the request thread, and clears them after, as T5 will. */
	static class StandInAgentFilter extends OncePerRequestFilter {
		@Override
		protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
				throws ServletException, IOException {
			try {
				if (callerTenant != null) {
					TenantContext.set(callerTenant);
					SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
							"agent", null, AuthorityUtils.createAuthorityList(callerAuthorities)));
				}
				chain.doFilter(request, response);
			} finally {
				TenantContext.clear();
				SecurityContextHolder.clearContext();
			}
		}
	}
}
