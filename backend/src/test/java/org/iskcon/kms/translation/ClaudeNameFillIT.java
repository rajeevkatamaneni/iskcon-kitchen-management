package org.iskcon.kms.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.testsupport.StubTokenVerifier;
import org.iskcon.kms.translation.StockNameFillService.ClaudeFillOutcome;
import org.iskcon.kms.translation.StockNameFillService.ClaudeFillResult;
import org.iskcon.kms.translation.StockNameFillService.NamesState;
import org.iskcon.kms.translation.StockNameFillService.NamesStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The Claude fill of stock names through the runner's {@code name-fill} profile (E10-NAMES-A).
 *
 * <p>A fake runner on a real port stands in for the runner: it reads the request the api sends,
 * records it, and answers the way {@code claude -p --output-format json --json-schema} does through
 * the runner (one {@code result} line carrying {@code structured_output}), with whatever answer the
 * test scripts. So the HTTP call, the NDJSON parsing and every check on the answer run for real.
 *
 * <p>No context of its own, like {@code AssistantApiIT}: no {@code @Import}, property or mock, so it
 * shares the suite's cached context. The fake's address is set on the client and put back after.
 *
 * <p>Every read and write goes through the application's unprivileged connection under RLS; the
 * assertions read through the privileged connection, which sees both temples, so a leak would show.
 */
@AutoConfigureMockMvc
class ClaudeNameFillIT extends AbstractIntegrationTest {

	private static final String ENSURE = "/api/v1/translation-glossary/ensure-names";
	private static final ObjectMapper JSON = new ObjectMapper();

	/** Telugu letters, to give each item a distinct, plausible name. */
	private static final String LETTERS = "కగచజటడతదనపబమయరలవసహ";

	private static HttpServer server;
	private static final List<JsonNode> requests = new CopyOnWriteArrayList<>();
	private static volatile Function<List<String>, String> answer;
	private static volatile int delayMs;
	private static volatile int httpStatus;

	@Autowired
	private StockNameFillService service;

	@Autowired
	private NameFillRunnerClient client;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private String originalRunnerUrl;
	private int phones;
	private UUID templeA;
	private UUID templeB;

	@BeforeAll
	static void startFake() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.setExecutor(Executors.newCachedThreadPool());
		server.createContext("/run", exchange -> {
			JsonNode body = JSON.readTree(exchange.getRequestBody());
			requests.add(body);
			try {
				Thread.sleep(delayMs);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			byte[] out = (httpStatus == 200 ? answer.apply(items(body.path("prompt").asText())) : "{}")
					.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
			exchange.sendResponseHeaders(httpStatus, out.length);
			exchange.getResponseBody().write(out);
			exchange.close();
		});
		server.start();
	}

	@AfterAll
	static void stopFake() {
		server.stop(0);
	}

	@BeforeEach
	void setUp() {
		TenantContext.clear();
		SecurityContextHolder.clearContext();
		admin = new JdbcTemplate(adminDataSource());
		originalRunnerUrl = client.runnerUrl();
		client.setRunnerUrl("http://127.0.0.1:" + server.getAddress().getPort());
		service.forgetAttempts();
		requests.clear();
		answer = ClaudeNameFillIT::goodAnswer;
		delayMs = 0;
		httpStatus = 200;

		templeA = tenant("names-a", "Names Temple A");
		templeB = tenant("names-b", "Names Temple B");
		user(templeA, "uid-names-a", "TEMPLE_ADMIN");
		user(templeA, "uid-names-vol", "VOLUNTEER");

		// Temple A: four stock items. A person named ghee in Telugu; Google named Mace earlier.
		// Saffron is in the recipe catalogue but not in the store.
		stock(templeA, "Ghee");
		stock(templeA, "Rice, broken");
		stock(templeA, "Mace");
		stock(templeA, "Toor Dal");
		ingredient(templeA, "Saffron");
		glossary(templeA, "te", "ghee", "నెయ్యి", null);
		glossary(templeA, "te", "Mace", "జాపత్రి", "google");

		// Temple B: its own items and nothing named. Nothing A does may reach it.
		stock(templeB, "Jaggery");
		stock(templeB, "Mace");
	}

	@AfterEach
	void tearDown() throws Exception {
		awaitIdle();
		TenantContext.clear();
		SecurityContextHolder.clearContext();
		client.setRunnerUrl(originalRunnerUrl);
		admin.execute("DELETE FROM translation_glossary");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("asks only for items with no name, read aloud, and stores them marked claude; never touches a person's or Google's")
	void fillsOnlyTheMissing() {
		ClaudeFillResult r = as(templeA, () -> service.fillWithClaude(templeA, "te"));

		assertThat(r.outcome()).isEqualTo(ClaudeFillOutcome.FILLED);
		assertThat(r.stockItems()).isEqualTo(4);
		assertThat(r.asked()).isEqualTo(2);
		assertThat(r.added()).isEqualTo(2);
		assertThat(r.rejected()).isZero();

		assertThat(requests).hasSize(1);
		JsonNode sent = requests.get(0);
		assertThat(sent.path("profile").asText()).isEqualTo("name-fill");
		assertThat(sent.has("agentToken")).as("no token for a run with no tools").isFalse();
		assertThat(sent.has("mcpUrl")).isFalse();
		assertThat(sent.path("prompt").asText()).startsWith("Language: Telugu (te), written in Telugu script.");
		assertThat(items(sent.path("prompt").asText())).containsExactly("Broken rice", "Toor Dal");

		Map<String, Map<String, Object>> a = glossaryOf(templeA, "te");
		assertThat(a).containsOnlyKeys("ghee", "Mace", "Rice, broken", "Toor Dal");
		assertThat(a.get("ghee").get("target_term")).isEqualTo("నెయ్యి");
		assertThat(a.get("ghee").get("machine_provider")).isNull();
		assertThat(a.get("Mace").get("target_term")).isEqualTo("జాపత్రి");
		assertThat(a.get("Mace").get("machine_provider")).isEqualTo("google");
		assertThat(a.get("Rice, broken").get("machine_provider")).isEqualTo("claude");
		assertThat(a.get("Rice, broken").get("machine_translated_at")).isNotNull();
		assertThat(a.get("Rice, broken").get("target_term")).isEqualTo(teluguName(1));
		assertThat(a.get("Toor Dal").get("target_term")).isEqualTo(teluguName(2));
		assertThat(glossaryOf(templeA, "kn")).isEmpty();
	}

	@Test
	@DisplayName("running it again asks nobody and changes nothing")
	void isIdempotent() {
		as(templeA, () -> service.fillWithClaude(templeA, "te"));
		Map<String, Map<String, Object>> first = glossaryOf(templeA, "te");

		ClaudeFillResult again = as(templeA, () -> service.fillWithClaude(templeA, "te"));
		assertThat(again.outcome()).isEqualTo(ClaudeFillOutcome.NOTHING_MISSING);
		assertThat(requests).hasSize(1);
		assertThat(glossaryOf(templeA, "te")).isEqualTo(first);
	}

	@Test
	@DisplayName("one temple's fill reads and writes only its own glossary")
	void isTenantScoped() {
		as(templeA, () -> service.fillWithClaude(templeA, "te"));
		assertThat(glossaryOf(templeB, "te")).isEmpty();

		ClaudeFillResult b = as(templeB, () -> service.fillWithClaude(templeB, "te"));
		assertThat(b.asked()).isEqualTo(2);
		assertThat(items(requests.get(1).path("prompt").asText())).containsExactly("Jaggery", "Mace");
		assertThat(glossaryOf(templeB, "te")).containsOnlyKeys("Jaggery", "Mace");
		assertThat(glossaryOf(templeA, "te")).hasSize(4);
	}

	@Test
	@DisplayName("the tenant must be the caller's own; English and unknown languages are handled")
	void tenantAndLanguageRules() {
		assertThatThrownBy(() -> as(templeA, () -> service.fillWithClaude(templeB, "te")))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> service.ensureNames(templeA, "te")).as("no tenant in context")
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> as(templeA, () -> service.ensureNames(templeA, "xx")))
				.isInstanceOf(ApplicationException.class);

		NamesState en = as(templeA, () -> service.ensureNames(templeA, "en"));
		assertThat(en.status()).isEqualTo(NamesStatus.READY);
		assertThat(en.named()).isEqualTo(4);
		assertThat(requests).isEmpty();
		assertThat(glossaryOf(templeA, "te")).hasSize(2);
	}

	@Test
	@DisplayName("ten ensureNames at once: one runner call, all FILLING, then READY; the background thread had the temple")
	void concurrentEnsureNamesCallsOnce() throws Exception {
		delayMs = 800;
		ExecutorService pool = Executors.newFixedThreadPool(10);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<NamesState>> results = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			results.add(pool.submit(() -> {
				go.await();
				return as(templeA, () -> service.ensureNames(templeA, "te"));
			}));
		}
		go.countDown();
		for (Future<NamesState> f : results) {
			NamesState s = f.get();
			assertThat(s.status()).isEqualTo(NamesStatus.FILLING);
			assertThat(s.stockItems()).isEqualTo(4);
			assertThat(s.named()).isEqualTo(2);
		}
		pool.shutdown();

		NamesState done = awaitReady(templeA, "te");
		assertThat(done.named()).isEqualTo(4);
		assertThat(requests).hasSize(1);
		// The rows were written by the background thread through the unprivileged connection. The
		// insert takes its tenant from app.tenant_id, so they exist only because that thread had
		// temple A set; and they are A's, not B's.
		assertThat(glossaryOf(templeA, "te")).containsKeys("Rice, broken", "Toor Dal");
		assertThat(glossaryOf(templeB, "te")).isEmpty();

		// Nothing missing now: READY at once, and no further call.
		assertThat(as(templeA, () -> service.ensureNames(templeA, "te")).status()).isEqualTo(NamesStatus.READY);
		assertThat(requests).hasSize(1);
	}

	@Test
	@DisplayName("two fills at once, past any in-memory guard (two api instances): the database lock lets one call the runner")
	void advisoryLockStopsASecondFill() throws Exception {
		delayMs = 800;
		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch go = new CountDownLatch(1);
		Callable<ClaudeFillResult> fill = () -> {
			go.await();
			return as(templeA, () -> service.fillWithClaude(templeA, "te"));
		};
		Future<ClaudeFillResult> one = pool.submit(fill);
		Future<ClaudeFillResult> two = pool.submit(fill);
		go.countDown();
		List<ClaudeFillOutcome> outcomes = List.of(one.get().outcome(), two.get().outcome());
		pool.shutdown();

		assertThat(outcomes).containsExactlyInAnyOrder(ClaudeFillOutcome.FILLED, ClaudeFillOutcome.ALREADY_FILLING);
		assertThat(requests).hasSize(1);
		assertThat(glossaryOf(templeA, "te")).hasSize(4);
		// And the lock was given back: another temple's and a later fill both get it.
		assertThat(as(templeA, () -> service.fillWithClaude(templeA, "te")).outcome())
				.isEqualTo(ClaudeFillOutcome.NOTHING_MISSING);
	}

	@Test
	@DisplayName("a bad answer stores only the items that pass: no blanks, no wrong script, no shifted or doubled answers")
	void badAnswersAreNotStored() {
		stock(templeA, "Jaggery");
		stock(templeA, "Curd");
		stock(templeA, "Coconut");
		stock(templeA, "Rice flour");
		// Numbered by where each item came in the message, whatever order the database chose.
		answer = items -> {
			int broken = items.indexOf("Broken rice") + 1;
			int coconut = items.indexOf("Coconut") + 1;
			int curd = items.indexOf("Curd") + 1;
			int jaggery = items.indexOf("Jaggery") + 1;
			int flour = items.indexOf("Rice flour") + 1;
			return result("""
					{"names":[
					 {"n":%d,"english":"Broken rice","name":"  నూక   బియ్యం "},
					 {"n":%d,"english":"Coconut","name":"   "},
					 {"n":%d,"english":"Curd","name":"Curd"},
					 {"n":%d,"english":"Rice flour","name":"బెల్లం"},
					 {"n":%d,"english":"Rice flour","name":"బియ్యపు పిండి"},
					 {"n":%d,"english":"Rice flour","name":"బియ్యం పిండి"},
					 {"n":9,"english":"Ghost","name":"దెయ్యం"}
					]}""".formatted(broken, coconut, curd, jaggery, flour, flour));
		};

		ClaudeFillResult r = as(templeA, () -> service.fillWithClaude(templeA, "te"));
		assertThat(r.outcome()).isEqualTo(ClaudeFillOutcome.FILLED);
		assertThat(r.asked()).isEqualTo(6);
		assertThat(r.added()).isEqualTo(1);
		assertThat(r.rejected()).isEqualTo(5);

		Map<String, Map<String, Object>> a = glossaryOf(templeA, "te");
		// Only Broken rice, tidied. Coconut blank, Curd in English, Jaggery's answer copied back the
		// wrong English (a shifted line), Rice flour answered twice, Toor Dal never answered.
		assertThat(a).containsOnlyKeys("ghee", "Mace", "Rice, broken");
		assertThat(a.get("Rice, broken").get("target_term")).isEqualTo("నూక బియ్యం");
	}

	@Test
	@DisplayName("a runner that fails stores nothing, says FAILED, and is not called again on the next message")
	void runnerFailureStoresNothing() throws Exception {
		answer = items -> "{\"type\":\"runner_error\",\"code\":\"timeout\",\"message\":\"x\"}\n";
		ClaudeFillResult r = as(templeA, () -> service.fillWithClaude(templeA, "te"));
		assertThat(r.outcome()).isEqualTo(ClaudeFillOutcome.FAILED);
		assertThat(glossaryOf(templeA, "te")).hasSize(2);

		answer = items -> "{\"type\":\"result\",\"is_error\":true,\"subtype\":\"error_max_turns\"}\n";
		assertThat(as(templeA, () -> service.fillWithClaude(templeA, "te")).outcome()).isEqualTo(ClaudeFillOutcome.FAILED);

		httpStatus = 503;
		assertThat(as(templeA, () -> service.ensureNames(templeA, "te")).status()).isEqualTo(NamesStatus.FILLING);
		awaitIdle();
		int calls = requests.size();
		assertThat(as(templeA, () -> service.ensureNames(templeA, "te")).status()).isEqualTo(NamesStatus.FAILED);
		assertThat(requests).hasSize(calls);
		assertThat(glossaryOf(templeA, "te")).hasSize(2);
	}

	@Test
	@DisplayName("the endpoint: a Temple Admin starts it and sees it finish; a Volunteer is refused")
	void endpoint() throws Exception {
		stubVerifier.accept("uid-names-vol");
		mvc.perform(authed(post(ENSURE).param("language", "te"))).andExpect(status().isForbidden());
		assertThat(requests).isEmpty();

		stubVerifier.accept("uid-names-a");
		mvc.perform(authed(post(ENSURE).param("language", "te")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.language").value("te"))
				.andExpect(jsonPath("$.status").value("FILLING"))
				.andExpect(jsonPath("$.stockItems").value(4))
				.andExpect(jsonPath("$.named").value(2));
		awaitIdle();
		mvc.perform(authed(post(ENSURE).param("language", "te")))
				.andExpect(jsonPath("$.status").value("READY"))
				.andExpect(jsonPath("$.named").value(4));
		mvc.perform(authed(post(ENSURE).param("language", "klingon"))).andExpect(status().isBadRequest());
	}

	// ---------------------------------------------------------------------

	/** The numbered items in a name-fill message, in order. */
	private static List<String> items(String prompt) {
		List<String> out = new ArrayList<>();
		for (String line : prompt.split("\n")) {
			if (line.matches("^\\d+\\. .*")) {
				out.add(line.substring(line.indexOf(". ") + 2));
			}
		}
		return out;
	}

	private static String teluguName(int n) {
		return "పేరు " + LETTERS.charAt(n % LETTERS.length());
	}

	/** Every item answered correctly, as Claude would. */
	private static String goodAnswer(List<String> items) {
		List<Map<String, Object>> names = new ArrayList<>();
		for (int i = 0; i < items.size(); i++) {
			names.add(Map.of("n", i + 1, "english", items.get(i), "name", teluguName(i + 1)));
		}
		try {
			return result(JSON.writeValueAsString(Map.of("names", names)));
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	/** One result line as the runner passes it through from {@code claude -p --output-format json}. */
	private static String result(String structuredOutput) {
		return "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"structured_output\":"
				+ structuredOutput.replace("\n", "") + "}\n";
	}

	private <T> T as(UUID tenant, Callable<T> body) {
		TenantContext.set(tenant);
		try {
			return body.call();
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalStateException(e);
		} finally {
			TenantContext.clear();
		}
	}

	/** Waits for this instance's background fills to finish (their runner calls are all answered). */
	private void awaitIdle() throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
		while (service.fillsRunning() > 0 && System.nanoTime() < deadline) {
			Thread.sleep(50);
		}
		assertThat(service.fillsRunning()).as("background fills finished").isZero();
	}

	private NamesState awaitReady(UUID tenant, String language) throws InterruptedException {
		awaitIdle();
		return as(tenant, () -> service.ensureNames(tenant, language));
	}

	private Map<String, Map<String, Object>> glossaryOf(UUID tenant, String language) {
		Map<String, Map<String, Object>> out = new HashMap<>();
		for (Map<String, Object> row : admin.queryForList("""
				SELECT source_term, target_term, machine_provider, machine_translated_at
				FROM translation_glossary WHERE tenant_id = ? AND language = ?
				""", tenant, language)) {
			out.put((String) row.get("source_term"), row);
		}
		return Collections.unmodifiableMap(out);
	}

	private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer " + StubTokenVerifier.TOKEN);
	}

	private UUID tenant(String slug, String name) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class, slug, name);
	}

	private void user(UUID tenant, String uid, String role) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test User', ?, ?, ?, 'ACTIVE')
				""", tenant, uid, uid + "@example.com", "+9198766%05d".formatted(++phones), role);
	}

	private UUID ingredient(UUID tenant, String name) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Test', 'KG') RETURNING id
				""", UUID.class, tenant, name);
	}

	private void stock(UUID tenant, String name) {
		UUID ingredient = ingredient(tenant, name);
		admin.update("""
				INSERT INTO inventory_items (tenant_id, ingredient_id, reorder_threshold)
				VALUES (?, ?, 1)
				""", tenant, ingredient);
	}

	private void glossary(UUID tenant, String language, String source, String target, String provider) {
		admin.update("""
				INSERT INTO translation_glossary (tenant_id, language, source_term, target_term,
					machine_translated_at, machine_provider)
				VALUES (?, ?, ?, ?, CASE WHEN ?::text IS NULL THEN NULL ELSE now() END, ?)
				""", tenant, language, source, target, provider, provider);
	}
}
