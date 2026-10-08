package org.iskcon.kms.assistant.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.assistant.token.AgentPrincipal;
import org.iskcon.kms.assistant.token.AgentTokenService;
import org.iskcon.kms.auth.TokenVerifier.VerifiedSubject;
import org.iskcon.kms.testsupport.StubTokenVerifier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The Kitchen Assistant's chat API end to end (E10-T10, T11): real Firebase-chain sign-in (stubbed
 * verifier, real users table), the real controller, store, budget and runner client, a real database
 * under RLS, and a {@link FakeRunner} on a real port playing back a recorded {@code claude -p} stream.
 *
 * <p><strong>No context of its own.</strong> This class adds no {@code @Import}, property or mock, so
 * it shares the suite's common context (memory note on the context cache). The runner's URL, the
 * deadline and the limits are pointed at this test's values through {@link AssistantSettings}'s
 * test-only setters in {@code @BeforeEach}, and put back in {@code @AfterEach}.
 */
class AssistantApiIT extends AbstractIntegrationTest {

	private static FakeRunner runner;

	@LocalServerPort
	private int port;

	@Autowired
	private StubTokenVerifier verifier;

	@Autowired
	private AssistantSettings settings;

	@Autowired
	private AgentTokenService tokens;

	@Autowired
	private MeterRegistry meters;

	@Autowired
	private ObjectMapper json;

	private final HttpClient http = HttpClient.newHttpClient();
	private JdbcTemplate admin;

	private UUID templeA;
	private UUID templeB;
	private UUID adminA;
	private UUID managerA;
	private UUID adminB;

	private String originalRunnerUrl;
	private Duration originalTimeout;
	private int originalPersonLimit;
	private int originalTempleLimit;

	@BeforeAll
	static void startRunner() throws IOException {
		runner = new FakeRunner();
	}

	@AfterAll
	static void stopRunner() {
		runner.close();
	}

	@BeforeEach
	void setUp() throws IOException {
		admin = new JdbcTemplate(adminDataSource());
		templeA = tenant("assistant-a", "Assistant Temple A");
		templeB = tenant("assistant-b", "Assistant Temple B");
		adminA = user(templeA, "uid-asst-admin-a", "TEMPLE_ADMIN", "+919876500101");
		managerA = user(templeA, "uid-asst-manager-a", "KITCHEN_MANAGER", "+919876500102");
		user(templeA, "uid-asst-staff-a", "KITCHEN_STAFF", "+919876500103");
		user(templeA, "uid-asst-volunteer-a", "VOLUNTEER", "+919876500104");
		adminB = user(templeB, "uid-asst-admin-b", "TEMPLE_ADMIN", "+919876500105");
		for (String uid : List.of("uid-asst-admin-a", "uid-asst-manager-a", "uid-asst-staff-a",
				"uid-asst-volunteer-a", "uid-asst-admin-b")) {
			verifier.accept(uid, new VerifiedSubject(uid, uid + "@example.com", null, false));
		}

		originalRunnerUrl = settings.runnerUrl().toString();
		originalTimeout = settings.runTimeout();
		originalPersonLimit = settings.perPersonDailyLimit();
		originalTempleLimit = settings.perTempleDailyLimit();
		settings.setRunnerUrl(runner.url());
		runner.reset(new FakeRunner.Script(200, RunStreamReaderTest.recording("runner-toor-dal.ndjson"), 5));
	}

	@AfterEach
	void tearDown() {
		settings.setRunnerUrl(originalRunnerUrl);
		settings.setRunTimeout(originalTimeout);
		settings.setPerPersonDailyLimit(originalPersonLimit);
		settings.setPerTempleDailyLimit(originalTempleLimit);
		admin.update("DELETE FROM assistant_pending_actions");
		admin.update("DELETE FROM assistant_messages");
		admin.update("DELETE FROM assistant_runs");
		admin.update("DELETE FROM assistant_conversations");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("a message: events in order, the reply and the run saved with their figures, the run audited")
	void oneMessage() throws Exception {
		double runsBefore = counter("assistant.runs", "status", "SUCCEEDED");
		UUID conversation = createConversation("uid-asst-admin-a");

		HttpResponse<String> response = send("uid-asst-admin-a", conversation, "how much toor dal do we have?");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
		List<Event> events = events(response.body());
		assertThat(events).extracting(Event::name).containsExactly("text", "text", "tool_call", "text", "text", "done");
		assertThat(events.get(0).data().get("text").asText()).isEqualTo("Let me ");
		assertThat(events.get(2).data().get("tool").asText()).isEqualTo("get_inventory");
		assertThat(events.get(2).data().get("summary").asText()).isEqualTo("item_name=toor dal");
		assertThat(events.get(2).data().has("result")).as("no tool result is relayed").isFalse();
		UUID messageId = UUID.fromString(events.get(5).data().get("messageId").asText());
		UUID runId = UUID.fromString(events.get(5).data().get("runId").asText());

		// What the runner was sent: the profile, the bare question (no history yet), this api's /mcp,
		// and an agent token for this person, this temple and this conversation, read and propose only.
		assertThat(runner.requests).hasSize(1);
		JsonNode sent = runner.requests.get(0);
		assertThat(sent.get("profile").asText()).isEqualTo("kitchen-assistant");
		assertThat(sent.get("prompt").asText()).isEqualTo(
				"Conversation language: English (en). Reply only in English, whatever language the message is"
						+ " written in. Change language only if the person asks you to, by calling"
						+ " set_conversation_language.\n\nhow much toor dal do we have?");
		assertThat(sent.get("mcpUrl").asText()).endsWith("/mcp");
		AgentPrincipal agent = tokens.verify(sent.get("agentToken").asText());
		assertThat(agent.userId()).isEqualTo(adminA);
		assertThat(agent.tenantId()).isEqualTo(templeA);
		assertThat(agent.conversationId()).isEqualTo(conversation);
		assertThat(agent.scopes()).containsExactlyInAnyOrder("agent:read", "agent:propose");

		Map<String, Object> run = admin.queryForMap("SELECT * FROM assistant_runs WHERE id = ?", runId);
		assertThat(run.get("status")).isEqualTo("SUCCEEDED");
		assertThat(run.get("tenant_id")).isEqualTo(templeA);
		assertThat(run.get("user_id")).isEqualTo(adminA);
		assertThat(run.get("model")).isEqualTo("claude-sonnet-4-5-20250929");
		assertThat(run.get("input_tokens")).isEqualTo(18012);
		assertThat(run.get("output_tokens")).isEqualTo(95);
		assertThat(run.get("notional_cost_usd").toString()).isEqualTo("0.041235");
		assertThat(run.get("tool_calls")).isEqualTo(1);
		assertThat(run.get("error_code")).isNull();
		assertThat(run.get("finished_at")).isNotNull();
		assertThat((Integer) run.get("duration_ms")).isNotNegative();

		List<Map<String, Object>> messages = admin.queryForList(
				"SELECT id, role, content, run_id FROM assistant_messages WHERE conversation_id = ? ORDER BY created_at",
				conversation);
		assertThat(messages).hasSize(2);
		assertThat(messages.get(0)).containsEntry("role", "USER").containsEntry("content", "how much toor dal do we have?");
		assertThat(messages.get(1)).containsEntry("role", "ASSISTANT").containsEntry("id", messageId)
				.containsEntry("run_id", runId)
				.containsEntry("content", "Let me check.\nWe have 806.088 Kg of Toor Dal on hand, well above the reorder level of 60 Kg.");

		Map<String, Object> audit = admin.queryForMap(
				"SELECT actor_user_id, entity_type, after_state::text AS after FROM audit_events WHERE action = 'ASSISTANT_RUN' AND entity_id = ?",
				runId);
		assertThat(audit.get("actor_user_id")).isEqualTo(adminA);
		assertThat(audit.get("entity_type")).isEqualTo("ASSISTANT_RUN");
		assertThat(audit.get("after").toString()).contains("SUCCEEDED").doesNotContain("toor dal");

		assertThat(counter("assistant.runs", "status", "SUCCEEDED")).isEqualTo(runsBefore + 1);

		// Read back through the API: titled from the first question, both messages in order.
		JsonNode list = json.readTree(get("uid-asst-admin-a", "/api/v1/assistant/conversations").body());
		assertThat(list).hasSize(1);
		assertThat(list.get(0).get("title").asText()).isEqualTo("how much toor dal do we have?");
		JsonNode detail = json.readTree(get("uid-asst-admin-a", "/api/v1/assistant/conversations/" + conversation).body());
		assertThat(detail.get("messages").findValuesAsText("role")).containsExactly("USER", "ASSISTANT");
	}

	@Test
	@DisplayName("the prompt carries the last 10 answered turns, oldest first, and leaves out an unanswered question")
	void historyWindow() throws Exception {
		UUID conversation = createConversation("uid-asst-admin-a");
		Instant at = Instant.now().minusSeconds(3600);
		for (int i = 1; i <= 12; i++) {
			message(conversation, "USER", "question " + i, at = at.plusSeconds(1));
			message(conversation, "ASSISTANT", "answer " + i, at = at.plusSeconds(1));
		}
		message(conversation, "USER", "the one that failed", at.plusSeconds(1));

		send("uid-asst-admin-a", conversation, "and rice?");

		String prompt = runner.requests.get(0).get("prompt").asText();
		assertThat(prompt).startsWith(AssistantConversationStore.languageLine("en")
				+ "Conversation so far (oldest first):\n\nUser: question 3\nAssistant: answer 3\n\n");
		assertThat(prompt).endsWith("User: question 12\nAssistant: answer 12\n\n"
				+ "New message from the user, to answer now:\nand rice?");
		assertThat(prompt).doesNotContain("question 2\n").doesNotContain("question 1\n")
				.doesNotContain("the one that failed");
		assertThat(prompt.lines().filter(line -> line.startsWith("User: "))).as("ten turns").hasSize(10);
	}

	@Test
	@DisplayName("a new conversation starts in the person's preferred language, and every prompt names it (E10-LANG)")
	void startsInPreferredLanguage() throws Exception {
		admin.update("UPDATE users SET preferred_language = 'kn' WHERE id = ?", adminA);
		UUID english = createConversation("uid-asst-manager-a");

		HttpResponse<String> created = post("uid-asst-admin-a", "/api/v1/assistant/conversations", "");
		assertThat(json.readTree(created.body()).get("language").asText()).isEqualTo("kn");
		UUID kannada = UUID.fromString(json.readTree(created.body()).get("id").asText());
		assertThat(admin.queryForObject("SELECT language FROM assistant_conversations WHERE id = ?", String.class, english))
				.as("a colleague with no preference starts in English").isEqualTo("en");

		send("uid-asst-admin-a", kannada, "how much toor dal do we have?");
		assertThat(runner.requests.get(0).get("prompt").asText())
				.startsWith("Conversation language: Kannada (kn). Reply only in Kannada, whatever language the"
						+ " message is written in. Change language only if the person asks you to, by calling"
						+ " set_conversation_language.\n\n")
				.endsWith("how much toor dal do we have?");

		// A preference changed later applies to the next conversation, not to this one.
		admin.update("UPDATE users SET preferred_language = 'hi' WHERE id = ?", adminA);
		runner.reset(new FakeRunner.Script(200, RunStreamReaderTest.recording("runner-toor-dal.ndjson"), 0));
		send("uid-asst-admin-a", kannada, "and rice?");
		assertThat(runner.requests.get(0).get("prompt").asText()).startsWith(AssistantConversationStore.languageLine("kn"));
		JsonNode detail = json.readTree(get("uid-asst-admin-a", "/api/v1/assistant/conversations/" + kannada).body());
		assertThat(detail.get("language").asText()).isEqualTo("kn");
	}

	@Test
	@DisplayName("a run that switches the language sends a language event before done; one that does not, sends none")
	void languageEventWhenSwitched() throws Exception {
		UUID conversation = createConversation("uid-asst-admin-a");
		// What set_conversation_language does, done by the fake runner while the run is under way.
		runner.duringRun = () -> admin.update("UPDATE assistant_conversations SET language = 'kn' WHERE id = ?", conversation);

		List<Event> events = events(send("uid-asst-admin-a", conversation, "please reply in Kannada").body());

		assertThat(events).extracting(Event::name)
				.containsExactly("text", "text", "tool_call", "text", "text", "language", "done");
		assertThat(events.get(5).data().get("language").asText()).isEqualTo("kn");
		assertThat(events.get(5).data().get("languageName").asText()).isEqualTo("Kannada");

		// The next message: no switch, so no event, and the prompt is now in Kannada.
		runner.reset(new FakeRunner.Script(200, RunStreamReaderTest.recording("runner-toor-dal.ndjson"), 0));
		List<Event> next = events(send("uid-asst-admin-a", conversation, "how much rice?").body());
		assertThat(next).extracting(Event::name).doesNotContain("language");
		assertThat(runner.requests.get(0).get("prompt").asText()).startsWith(AssistantConversationStore.languageLine("kn"));
	}

	@Test
	@DisplayName("Kitchen Staff and Volunteers are refused every endpoint, and nothing reaches the runner")
	void refusedWithoutPermission() throws Exception {
		UUID conversation = createConversation("uid-asst-admin-a");
		for (String uid : List.of("uid-asst-staff-a", "uid-asst-volunteer-a")) {
			assertThat(post(uid, "/api/v1/assistant/conversations", "").statusCode()).as(uid).isEqualTo(403);
			assertThat(get(uid, "/api/v1/assistant/conversations").statusCode()).as(uid).isEqualTo(403);
			assertThat(get(uid, "/api/v1/assistant/conversations/" + conversation).statusCode()).as(uid).isEqualTo(403);
			assertThat(send(uid, conversation, "hello").statusCode()).as(uid).isEqualTo(403);
		}
		assertThat(runner.requests).isEmpty();
	}

	@Test
	@DisplayName("another person at the same temple cannot read or write to your conversation: 404, as if it did not exist")
	void anotherPersonsConversation() throws Exception {
		UUID conversation = createConversation("uid-asst-admin-a");
		send("uid-asst-admin-a", conversation, "how much toor dal do we have?");
		runner.requests.clear();

		HttpResponse<String> read = get("uid-asst-manager-a", "/api/v1/assistant/conversations/" + conversation);
		assertThat(read.statusCode()).isEqualTo(404);
		assertThat(json.readTree(read.body()).get("code").asText()).isEqualTo("KMS-400030");
		assertThat(read.body()).doesNotContain("toor dal");
		assertThat(send("uid-asst-manager-a", conversation, "and rice?").statusCode()).isEqualTo(404);
		assertThat(json.readTree(get("uid-asst-manager-a", "/api/v1/assistant/conversations").body())).isEmpty();
		assertThat(runner.requests).isEmpty();
		assertThat(admin.queryForObject("SELECT count(*) FROM assistant_messages WHERE conversation_id = ?",
				Integer.class, conversation)).isEqualTo(2);
	}

	@Test
	@DisplayName("another temple cannot read or write to it either (RLS): 404, and its own list is empty")
	void anotherTemplesConversation() throws Exception {
		UUID conversation = createConversation("uid-asst-admin-a");
		send("uid-asst-admin-a", conversation, "how much toor dal do we have?");
		runner.requests.clear();

		assertThat(get("uid-asst-admin-b", "/api/v1/assistant/conversations/" + conversation).statusCode()).isEqualTo(404);
		assertThat(send("uid-asst-admin-b", conversation, "and rice?").statusCode()).isEqualTo(404);
		assertThat(json.readTree(get("uid-asst-admin-b", "/api/v1/assistant/conversations").body())).isEmpty();
		assertThat(runner.requests).isEmpty();
	}

	@Test
	@DisplayName("a person's daily limit: KMS-400192 as JSON before anything is spent, counting a run still going")
	void personalLimit() throws Exception {
		settings.setPerPersonDailyLimit(2);
		double refusalsBefore = counter("assistant.budget_refusals", "scope", "person");
		UUID conversation = createConversation("uid-asst-admin-a");

		assertThat(send("uid-asst-admin-a", conversation, "first").statusCode()).isEqualTo(200);
		// A run still going counts: written straight to the table, as a run in flight would be.
		admin.update("INSERT INTO assistant_runs (tenant_id, conversation_id, user_id, model) VALUES (?, ?, ?, 'sonnet')",
				templeA, conversation, adminA);
		runner.requests.clear();

		HttpResponse<String> refused = send("uid-asst-admin-a", conversation, "third");
		assertThat(refused.statusCode()).isEqualTo(429);
		assertThat(refused.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
		JsonNode error = json.readTree(refused.body());
		assertThat(error.get("code").asText()).isEqualTo("KMS-400192");
		assertThat(error.get("message").asText()).isNotBlank();
		assertThat(error.get("action").asText()).isNotBlank();
		assertThat(runner.requests).as("the runner is never called").isEmpty();
		assertThat(admin.queryForObject("SELECT count(*) FROM assistant_messages WHERE content = 'third'", Integer.class))
				.as("the refused question is not saved").isZero();
		assertThat(admin.queryForObject("SELECT count(*) FROM assistant_runs", Integer.class)).isEqualTo(2);
		assertThat(counter("assistant.budget_refusals", "scope", "person")).isEqualTo(refusalsBefore + 1);

		// Somebody else at the same temple still can.
		UUID theirs = createConversation("uid-asst-manager-a");
		assertThat(send("uid-asst-manager-a", theirs, "first").statusCode()).isEqualTo(200);
	}

	@Test
	@DisplayName("the temple's daily limit: KMS-400193 for everyone there, and not for another temple")
	void templeLimit() throws Exception {
		settings.setPerTempleDailyLimit(1);
		double refusalsBefore = counter("assistant.budget_refusals", "scope", "temple");
		UUID mine = createConversation("uid-asst-admin-a");
		assertThat(send("uid-asst-admin-a", mine, "first").statusCode()).isEqualTo(200);
		runner.requests.clear();

		UUID theirs = createConversation("uid-asst-manager-a");
		HttpResponse<String> refused = send("uid-asst-manager-a", theirs, "mine too?");
		assertThat(refused.statusCode()).isEqualTo(429);
		assertThat(json.readTree(refused.body()).get("code").asText()).isEqualTo("KMS-400193");
		assertThat(runner.requests).isEmpty();
		assertThat(counter("assistant.budget_refusals", "scope", "temple")).isEqualTo(refusalsBefore + 1);

		UUID templeBs = createConversation("uid-asst-admin-b");
		assertThat(send("uid-asst-admin-b", templeBs, "first").statusCode()).as("temple B's day is its own").isEqualTo(200);
	}

	@Test
	@DisplayName("runner down: an error event with KMS-500008, the run FAILED with that code, no reply saved")
	void runnerDown() throws Exception {
		settings.setRunnerUrl("http://127.0.0.1:" + closedPort());
		UUID conversation = createConversation("uid-asst-admin-a");

		HttpResponse<String> response = send("uid-asst-admin-a", conversation, "how much toor dal do we have?");

		assertThat(response.statusCode()).isEqualTo(200);
		List<Event> events = events(response.body());
		assertThat(events).extracting(Event::name).containsExactly("error");
		assertThat(events.get(0).data().get("code").asText()).isEqualTo("KMS-500008");
		assertThat(events.get(0).data().get("message").asText()).isEqualTo("The assistant isn't available right now.");
		assertFinishedWithoutReply(conversation, "FAILED", "KMS-500008");
	}

	@Test
	@DisplayName("runner busy (503): the same KMS-500008")
	void runnerBusy() throws Exception {
		runner.reset(new FakeRunner.Script(503, List.of(), 0));
		UUID conversation = createConversation("uid-asst-admin-a");

		List<Event> events = events(send("uid-asst-admin-a", conversation, "hello").body());

		assertThat(events).extracting(Event::name).containsExactly("error");
		assertThat(events.get(0).data().get("code").asText()).isEqualTo("KMS-500008");
		assertFinishedWithoutReply(conversation, "FAILED", "KMS-500008");
	}

	@Test
	@DisplayName("the api's own deadline: an error event with KMS-500009 and the run TIMED_OUT")
	void apiDeadline() throws Exception {
		settings.setRunTimeout(Duration.ofSeconds(1));
		runner.reset(new FakeRunner.Script(200, RunStreamReaderTest.recording("runner-toor-dal.ndjson"), 3000));
		UUID conversation = createConversation("uid-asst-admin-a");

		long started = System.nanoTime();
		List<Event> events = events(send("uid-asst-admin-a", conversation, "hello").body());
		long tookMs = (System.nanoTime() - started) / 1_000_000;

		assertThat(events).extracting(Event::name).containsExactly("error");
		assertThat(events.get(0).data().get("code").asText()).isEqualTo("KMS-500009");
		assertThat(tookMs).as("stopped at the deadline, not when the runner finished").isLessThan(2900);
		assertFinishedWithoutReply(conversation, "TIMED_OUT", "KMS-500009");
		await().atMost(Duration.ofSeconds(10)).until(() -> runner.callerLeft);
	}

	@Test
	@DisplayName("the runner's own timeout line: the text shown so far, then KMS-500009, and the run TIMED_OUT")
	void runnerTimeout() throws Exception {
		runner.reset(new FakeRunner.Script(200, List.of(
				"{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Let me check.\"}}}",
				"{\"type\":\"runner_error\",\"code\":\"timeout\",\"message\":\"killed at 60000 ms\"}"), 0));
		UUID conversation = createConversation("uid-asst-admin-a");

		List<Event> events = events(send("uid-asst-admin-a", conversation, "hello").body());

		assertThat(events).extracting(Event::name).containsExactly("text", "error");
		assertThat(events.get(1).data().get("code").asText()).isEqualTo("KMS-500009");
		assertFinishedWithoutReply(conversation, "TIMED_OUT", "KMS-500009");
	}

	@Test
	@DisplayName("the browser leaves mid-answer: the runner call is cut off and the run is closed FAILED with no code")
	void browserLeaves() throws Exception {
		List<String> slow = new ArrayList<>();
		for (int i = 0; i < 200; i++) {
			slow.add("{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"word " + i + " \"}}}");
		}
		runner.reset(new FakeRunner.Script(200, slow, 50));
		UUID conversation = createConversation("uid-asst-admin-a");

		HttpResponse<InputStream> response = http.send(messageRequest("uid-asst-admin-a", conversation, "talk"),
				HttpResponse.BodyHandlers.ofInputStream());
		try (InputStream body = response.body()) {
			byte[] buffer = new byte[256];
			int read = body.read(buffer);
			assertThat(read).isPositive();
			assertThat(new String(buffer, 0, read)).contains("run ");
		}

		await().atMost(Duration.ofSeconds(15)).until(() -> !"RUNNING".equals(admin.queryForObject(
				"SELECT status FROM assistant_runs WHERE conversation_id = ?", String.class, conversation)));
		assertFinishedWithoutReply(conversation, "FAILED", null);
		await().atMost(Duration.ofSeconds(15)).until(() -> runner.callerLeft);
	}

	@Test
	@DisplayName("a blank message is refused as a form error, before any run")
	void blankMessage() throws Exception {
		UUID conversation = createConversation("uid-asst-admin-a");
		HttpResponse<String> response = send("uid-asst-admin-a", conversation, "   ");
		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(json.readTree(response.body()).get("code").asText()).isEqualTo("KMS-400001");
		assertThat(runner.requests).isEmpty();
		assertThat(admin.queryForObject("SELECT count(*) FROM assistant_runs", Integer.class)).isZero();
	}

	// --- helpers ---

	private void assertFinishedWithoutReply(UUID conversation, String status, String code) {
		Map<String, Object> run = admin.queryForMap(
				"SELECT status, error_code, finished_at, duration_ms FROM assistant_runs WHERE conversation_id = ?", conversation);
		assertThat(run.get("status")).isEqualTo(status);
		assertThat(run.get("error_code")).isEqualTo(code);
		assertThat(run.get("finished_at")).isNotNull();
		assertThat(run.get("duration_ms")).isNotNull();
		assertThat(admin.queryForList("SELECT role FROM assistant_messages WHERE conversation_id = ?", String.class, conversation))
				.containsExactly("USER");
		assertThat(admin.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'ASSISTANT_RUN'", Integer.class))
				.isEqualTo(1);
	}

	private record Event(String name, JsonNode data) {
	}

	private List<Event> events(String body) throws IOException {
		List<Event> events = new ArrayList<>();
		for (String frame : body.split("\n\n")) {
			if (frame.isBlank() || frame.startsWith(":")) {
				continue;
			}
			String name = null;
			String data = null;
			for (String line : frame.split("\n")) {
				if (line.startsWith("event: ")) {
					name = line.substring(7);
				} else if (line.startsWith("data: ")) {
					data = line.substring(6);
				}
			}
			events.add(new Event(name, json.readTree(data)));
		}
		return events;
	}

	private UUID createConversation(String uid) throws Exception {
		HttpResponse<String> created = post(uid, "/api/v1/assistant/conversations", "");
		assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
		return UUID.fromString(json.readTree(created.body()).get("id").asText());
	}

	private HttpResponse<String> send(String uid, UUID conversation, String text) throws Exception {
		return http.send(messageRequest(uid, conversation, text), HttpResponse.BodyHandlers.ofString());
	}

	private HttpRequest messageRequest(String uid, UUID conversation, String text) throws Exception {
		return HttpRequest.newBuilder(uri("/api/v1/assistant/conversations/" + conversation + "/messages"))
				.header("Authorization", "Bearer " + uid)
				.header("Content-Type", "application/json")
				.header("Accept", "text/event-stream, application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("text", text))))
				.build();
	}

	private HttpResponse<String> post(String uid, String path, String body) throws Exception {
		return http.send(HttpRequest.newBuilder(uri(path))
				.header("Authorization", "Bearer " + uid)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body))
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

	private double counter(String name, String tag, String value) {
		var counter = meters.find(name).tag(tag, value).counter();
		return counter == null ? 0 : counter.count();
	}

	private static int closedPort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
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

	private void message(UUID conversation, String role, String content, Instant at) {
		admin.update("""
				INSERT INTO assistant_messages (tenant_id, conversation_id, role, content, created_at)
				VALUES (?, ?, ?, ?, ?)
				""", templeA, conversation, role, content, Timestamp.from(at));
	}
}
