package org.iskcon.kms.perf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * How many statements one send of a temple communication costs (T-097).
 *
 * <h2>Which side of the fix these numbers are on: the fixed side</h2>
 *
 * <p><strong>This class pins the behaviour after T-097, not the defect it replaced.</strong> It was
 * written first as a measurement — the ledger's row carried an honest caveat that nobody had ever
 * counted a 400-person send, and said not to schedule the work until somebody had — so for one
 * afternoon it asserted the defect's own figures. It does not any more, and the distinction matters
 * enough to say out loud: a test that pins a number is worthless unless a reader can tell whether
 * the number is the thing being kept or the thing being removed.
 *
 * <p><strong>What is kept:</strong> one send asks the database for the temple's name <em>once</em>,
 * however many people it is going to. {@code send()} reads it before the loop and hands it to
 * {@code queueFor}; {@code retryWithin()} and {@code sendTest()} do the same.
 *
 * <h2>What was there before, because the mechanism is the interesting part</h2>
 *
 * <p>The ledger's row reads "a 400-person send makes 400 identical queries". That was wrong in
 * mechanism as well as in magnitude. {@code templeName()} was a bare query inside {@code queueFor},
 * and {@code send()} deliberately holds no transaction across its loop — T-094 split it that way so
 * that one devotee the relay refuses does not abandon the other 399 — so nothing was holding a
 * connection and <em>every</em> call checked one out of the pool. {@code TenantAwareDataSource}
 * stamps every checkout with eight {@code set_config} and clears it on return with five
 * {@code RESET}. So one lookup cost <strong>fourteen statements, not one</strong>:
 *
 * <pre>
 *   40 recipients, before: 1,833 statements, 560 of them templeName  (30.5% of the send)
 *   40 recipients, after:    1,287 statements,  14 of them templeName  (1.1% of the send)
 *   400 recipients, before: ~5,600 statements spent on the name; after, 14.
 * </pre>
 *
 * <h2>Why forty recipients, and why the twenty-recipient case stays</h2>
 *
 * <p>A per-recipient cost is invisible in a one-recipient fixture — one query looks the same however
 * it was arrived at — so forty is the smallest audience at which the old shape and the new one could
 * not be confused. {@link #theCostDoesNotScaleWithTheAudience()} is the control that tells a real
 * improvement from a coincidence: it halves the audience and requires the count <em>not</em> to
 * move. A fix that merely made the per-recipient lookup cheaper would pass the first test's spirit
 * and fail this one.
 *
 * <h2>What is and is not measured</h2>
 *
 * <p>The window is the {@code POST /api/v1/communications/{id}/send} request and nothing else. The
 * relay half — one Quartz job per notification, each in its own transaction — is deliberately out of
 * it: the scheduler is mocked, exactly as {@code CommunicationIT} mocks it, so no send job runs.
 * That matters for reading the result honestly. {@code SmtpEmailAdapter} asks
 * {@code TenantEmailIdentityService} for the same temple name once per message it sends, but each of
 * those sits alone in its own job and its own transaction, so there is nothing there to hoist; the
 * repetition this class counts is the one that happened inside a single request.
 */
@AutoConfigureMockMvc
@Import(PerfStubVerifierConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommunicationSendStatementCountIT extends AbstractIntegrationTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	/** Enough people that a per-recipient cost could not be mistaken for a fixed one. */
	private static final int RECIPIENTS = 40;

	/**
	 * What one {@code templeName()} costs on the send path when nothing is holding a connection.
	 *
	 * <p>{@code send()} holds no transaction across its loop (T-094), so the one call it now makes
	 * borrows a connection of its own and {@code TenantAwareDataSource} stamps it: eight
	 * {@code set_config} on the way out, five {@code RESET} on the way back, and the one statement
	 * anybody meant to send. Fourteen. The whole of T-097 is that this is paid <em>once</em> instead
	 * of once per recipient.
	 */
	private static final int STATEMENTS_PER_POOLED_LOOKUP = 14;

	/**
	 * Every statement one 40-person send sends, measured on the tree of 2026-09-11 after the hoist.
	 * It was 1,833 before. Pinned so that the share the name lookup accounts for stays readable, and
	 * so that a second thing creeping into the loop is a failure rather than a shrug.
	 */
	private static final int TOTAL_STATEMENTS_PER_SEND = 1287;

	/** The frame that issues the lookup under examination. */
	private static final String TEMPLE_NAME_LOOKUP = "communication.CommunicationService.templeName";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private PerfStubVerifierConfiguration.PerfStubTokenVerifier stubVerifier;

	/**
	 * Mocked for the reason {@code CommunicationIT} mocks it: Quartz is excluded from this suite's
	 * contexts, so {@code NotificationService.enqueueSend} would otherwise find no scheduler and
	 * refuse. With it mocked the send runs to completion and queues nothing, which is exactly the
	 * request-side window this class is counting.
	 */
	@MockBean
	private Scheduler scheduler;

	private JdbcTemplate admin;
	private UUID tenant;

	@BeforeAll
	void buildTheTemple() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();

		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('t097-temple', 'T-097 Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status,
						contact_consent_at)
				VALUES (?, 't097-admin', 'T-097 Admin', 't097-admin@example.com', '+919876509001',
						'TEMPLE_ADMIN', 'ACTIVE', now())
				""", tenant);

		for (int i = 0; i < RECIPIENTS; i++) {
			// Consented, so every one of them is in the audience. A devotee who had not consented
			// would be suppressed inside notify() and the send would do less work for them, which
			// would make the count below a number nobody could reason about.
			admin.update("""
					INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status,
							contact_consent_at)
					VALUES (?, ?, ?, ?, ?, 'VOLUNTEER', 'ACTIVE', now())
					""", tenant, "t097-dev-" + i, "Devotee " + i,
					"t097-dev-" + i + "@example.com", String.format("+9198765%05d", 10000 + i));
		}

		stubVerifier.accept("t097-admin");
	}

	@AfterAll
	void removeTheTemple() {
		if (tenant == null) {
			return;
		}
		admin.query("SELECT delete_tenant_cascade(?)", rs -> { }, tenant);
		admin.update("DELETE FROM tenants WHERE id = ?", tenant);
	}

	@Test
	@DisplayName("one send asks the database for the temple's name once, whatever the audience (T-097)")
	void theTempleNameIsLookedUpOnce() throws Exception {
		String id = draftANewsletter();

		StatementRecorder.start();
		mvc.perform(authed(post("/api/v1/communications/{id}/send", id)))
				.andExpect(status().isOk());
		List<StatementRecorder.Executed> statements = StatementRecorder.stop();

		// Printed before anything is asserted, so a failure still leaves the whole picture behind it.
		// The proof file quotes this block, and a number nobody can regenerate is a number that rots.
		report(statements);

		long attributedToTheLookup = sentBy(statements, TEMPLE_NAME_LOOKUP);
		long realLookups = statements.stream()
				.filter(e -> e.caller().startsWith(TEMPLE_NAME_LOOKUP))
				.filter(e -> e.sql().contains("SELECT name FROM tenants"))
				.count();

		// Softly, so a failure reports every number rather than the first one. These are three
		// separate claims and a reader who sees only one of them cannot tell what went wrong.
		assertSoftly(softly -> {
			softly.assertThat(realLookups)
					.as("SELECT name FROM tenants, issued from CommunicationService.templeName, for one"
							+ " send to " + RECIPIENTS + " people. send() reads it before the loop and hands it"
							+ " to queueFor, so it is one however large the audience is. It was "
							+ RECIPIENTS + " (T-097)")
					.isEqualTo(1);

			softly.assertThat(attributedToTheLookup)
					.as("every statement that one lookup costs. send() holds no transaction across the"
							+ " loop (T-094), so the call borrows a connection and TenantAwareDataSource stamps"
							+ " it: eight set_config out, five RESET back, then the query. Fourteen — paid once"
							+ " now rather than once a head (T-097)")
					.isEqualTo(STATEMENTS_PER_POOLED_LOOKUP);

			softly.assertThat(statements.size())
					.as("every statement one send sends, so the line above can be read as a share of the"
							+ " whole rather than on its own. 1,833 before the hoist, " + TOTAL_STATEMENTS_PER_SEND
							+ " after (T-097)")
					.isEqualTo(TOTAL_STATEMENTS_PER_SEND);
		});
	}

	@Test
	@DisplayName("the cost does not scale with the audience: half the people, the same one lookup")
	void theCostDoesNotScaleWithTheAudience() throws Exception {
		// The control that separates a real fix from a coincidence, built into the class rather than
		// left to a script. A cost paid once and a cost paid per recipient are indistinguishable at
		// one audience size; they are not at two. Before T-097 this read 280 against the other test's
		// 560 — a straight line through the origin. It must now be flat.
		//
		// Presence is the opt-out: the table holds one row per kind of message a devotee has
		// declined, so twenty rows means twenty people out of the audience.
		admin.update("""
				INSERT INTO communication_preferences (tenant_id, user_id, category)
				SELECT ?, id, 'NEWSLETTER' FROM users
				WHERE tenant_id = ?
				  AND firebase_uid IN (SELECT 't097-dev-' || g FROM generate_series(0, 19) g)
				""", tenant, tenant);
		try {
			String id = draftANewsletter();

			StatementRecorder.start();
			mvc.perform(authed(post("/api/v1/communications/{id}/send", id)))
					.andExpect(status().isOk());
			List<StatementRecorder.Executed> statements = StatementRecorder.stop();

			assertThat(sentBy(statements, TEMPLE_NAME_LOOKUP))
					.as("twenty of the forty declined this kind of message, so twenty copies are queued"
							+ " and the temple's name still costs exactly what it costs at forty. A fix that"
							+ " only made the per-recipient lookup cheaper would pass the other test's spirit"
							+ " and fail this one (T-097)")
					.isEqualTo(STATEMENTS_PER_POOLED_LOOKUP);
		} finally {
			admin.update("DELETE FROM communication_preferences WHERE tenant_id = ?", tenant);
		}
	}

	// ---------------------------------------------------------------------------------------------

	/** The whole recording, by the application frame that issued each statement. */
	private static void report(List<StatementRecorder.Executed> statements) {
		System.out.println("T-097: one send to " + RECIPIENTS + " recipients sent "
				+ statements.size() + " statements in total.");
		statements.stream()
				.collect(java.util.stream.Collectors.groupingBy(
						e -> e.caller().replaceAll(":\\d+$", ""),
						java.util.TreeMap::new, java.util.stream.Collectors.counting()))
				.entrySet().stream()
				.sorted(java.util.Map.Entry.<String, Long>comparingByValue().reversed())
				.forEach(e -> System.out.printf("  %5d  %s%n", e.getValue(), e.getKey()));

		System.out.println("T-097: the statements attributed to templeName, by SQL:");
		statements.stream()
				.filter(e -> e.caller().startsWith(TEMPLE_NAME_LOOKUP))
				.collect(java.util.stream.Collectors.groupingBy(
						StatementRecorder.Executed::oneLine,
						java.util.TreeMap::new, java.util.stream.Collectors.counting()))
				.forEach((sql, count) -> System.out.printf("  %5d  %s%n", count, sql));
	}

	private String draftANewsletter() throws Exception {
		String body = mvc.perform(authed(post("/api/v1/communications"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"category\":\"NEWSLETTER\",\"channel\":\"EMAIL\","
								+ "\"subject\":\"Janmashtami\",\"bodyHtml\":\"<p>Come early</p>\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return JSON.readTree(body).get("id").asText();
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization",
				"Bearer " + PerfStubVerifierConfiguration.PerfStubTokenVerifier.TOKEN);
	}

	private static long sentBy(List<StatementRecorder.Executed> statements, String callerPrefix) {
		return statements.stream().filter(s -> s.caller().startsWith(callerPrefix)).count();
	}
}
