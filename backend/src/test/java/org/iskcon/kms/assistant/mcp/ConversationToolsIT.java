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
import org.iskcon.kms.assistant.token.AgentTokenFilterIT;
import org.iskcon.kms.assistant.token.AgentTokenService;
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
 * {@code set_conversation_language} end to end (E10-LANG): a real agent token through the real
 * {@code /mcp} chain and SDK into the tool, against the database under RLS.
 *
 * <p>The conversation is the token's {@code conv} claim and nothing else, and it has to be the
 * token's own person's: a token naming a colleague's conversation, or another temple's, changes
 * nothing and is told so in words. Annotations identical to {@link McpEndToEndIT}'s so the two share
 * one application context (see that class's comment).
 */
@Import(AgentTokenFilterIT.EchoEndpoint.class)
@TestPropertySource(properties = "kms.assistant.token.key=" + AgentTokenFilterIT.KEY_BASE64)
class ConversationToolsIT extends AbstractIntegrationTest {

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
	private UUID colleagueA;
	private UUID adminB;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		fixture = new McpStockFixture(admin);
		adminA = admin.queryForObject("SELECT id FROM users WHERE firebase_uid = 'uid-mcp-a'", UUID.class);
		adminB = admin.queryForObject("SELECT id FROM users WHERE firebase_uid = 'uid-mcp-b'", UUID.class);
		colleagueA = admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-mcp-colleague', 'Test Colleague', 'mcp-colleague@example.com', '+919876500093',
				        'KITCHEN_MANAGER', 'ACTIVE')
				RETURNING id
				""", UUID.class, fixture.templeA);
	}

	@AfterEach
	void tearDown() {
		admin.update("DELETE FROM assistant_conversations");
		McpStockFixture.tearDown(admin);
	}

	@Test
	@DisplayName("switches the token's own conversation, and returns the language by code and name")
	void switchesOwnConversation() throws Exception {
		UUID mine = conversation(fixture.templeA, adminA, "kn");
		UUID colleagues = conversation(fixture.templeA, colleagueA, "kn");

		JsonNode result = result(call(token(adminA, fixture.templeA, mine), "{\"language\":\"en\"}"));

		assertThat(result.get("isError").asBoolean()).as(result.toString()).isFalse();
		JsonNode payload = objectMapper.readTree(result.get("content").get(0).get("text").asText());
		assertThat(payload.get("language").asText()).isEqualTo("en");
		assertThat(payload.get("language_name").asText()).isEqualTo("English");
		assertThat(languageOf(mine)).isEqualTo("en");
		assertThat(languageOf(colleagues)).as("nobody else's conversation moves").isEqualTo("kn");
	}

	@Test
	@DisplayName("an unknown code is refused in words, and the conversation keeps its language")
	void unknownLanguage() throws Exception {
		UUID mine = conversation(fixture.templeA, adminA, "kn");

		for (String arguments : new String[] {"{\"language\":\"french\"}", "{\"language\":\"KN\"}", "{}"}) {
			JsonNode result = result(call(token(adminA, fixture.templeA, mine), arguments));
			assertThat(result.get("isError").asBoolean()).as(arguments).isTrue();
			assertThat(result.get("content").get(0).get("text").asText())
					.as("the model is told which codes exist").contains("is not a language").contains("kn, ks");
		}
		assertThat(languageOf(mine)).isEqualTo("kn");
	}

	@Test
	@DisplayName("a token naming a colleague's conversation at the same temple changes nothing")
	void colleaguesConversationRefused() throws Exception {
		UUID colleagues = conversation(fixture.templeA, colleagueA, "kn");

		JsonNode result = result(call(token(adminA, fixture.templeA, colleagues), "{\"language\":\"en\"}"));

		assertThat(result.get("isError").asBoolean()).isTrue();
		assertThat(result.get("content").get(0).get("text").asText()).isEqualTo(ConversationTools.NOT_FOUND_MESSAGE);
		assertThat(languageOf(colleagues)).isEqualTo("kn");
	}

	@Test
	@DisplayName("a token naming another temple's conversation changes nothing (RLS hides it)")
	void otherTemplesConversationRefused() throws Exception {
		UUID theirs = conversation(fixture.templeB, adminB, "kn");

		JsonNode result = result(call(token(adminA, fixture.templeA, theirs), "{\"language\":\"en\"}"));

		assertThat(result.get("isError").asBoolean()).isTrue();
		assertThat(result.get("content").get(0).get("text").asText()).isEqualTo(ConversationTools.NOT_FOUND_MESSAGE);
		assertThat(languageOf(theirs)).isEqualTo("kn");
	}

	@Test
	@DisplayName("the database refuses a language outside the list on a conversation (V162 CHECK)")
	void databaseRefusesUnknownLanguage() {
		UUID mine = conversation(fixture.templeA, adminA, "en");
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> admin.update(
						"UPDATE assistant_conversations SET language = 'xx' WHERE id = ?", mine))
				.hasMessageContaining("assistant_conversations_language_known");
	}

	// ---------------------------------------------------------------------

	private UUID conversation(UUID tenant, UUID user, String language) {
		return admin.queryForObject("""
				INSERT INTO assistant_conversations (tenant_id, user_id, language) VALUES (?, ?, ?) RETURNING id
				""", UUID.class, tenant, user, language);
	}

	private String languageOf(UUID conversation) {
		return admin.queryForObject("SELECT language FROM assistant_conversations WHERE id = ?", String.class, conversation);
	}

	private String token(UUID user, UUID tenant, UUID conversation) {
		return tokens.mint(user, tenant, conversation, Set.of(AgentTokenService.SCOPE_READ));
	}

	private HttpResponse<String> call(String token, String arguments) throws Exception {
		return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
				.header("Accept", "application/json, text/event-stream")
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + token)
				.POST(HttpRequest.BodyPublishers.ofString("""
						{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"set_conversation_language","arguments":%s}}"""
						.formatted(arguments)))
				.build(), HttpResponse.BodyHandlers.ofString());
	}

	private JsonNode result(HttpResponse<String> response) throws Exception {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		JsonNode envelope = objectMapper.readTree(response.body());
		assertThat(envelope.has("error")).as(response.body()).isFalse();
		return envelope.get("result");
	}
}
