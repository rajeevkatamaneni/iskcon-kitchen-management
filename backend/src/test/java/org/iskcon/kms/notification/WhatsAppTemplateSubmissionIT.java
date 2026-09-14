package org.iskcon.kms.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.stream.Collectors;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.GlobalExceptionHandler;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.tenancy.TenantSecretStore;
import org.iskcon.kms.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Saving Settings → WhatsApp registers the templates, and the date that says so reaches the screen
 * (T-159).
 *
 * <p><strong>What staging showed, 2026-09-12.</strong> A save that logged "Submitted 14 of 20" and
 * returned 200, followed a minute later by a signed-in GET answering {@code templatesSubmittedAt:
 * null}, while the phone number id, account id and display number from the same save had all been
 * updated. So either the stamp never landed in {@code tenant_settings}, or it landed and the answer
 * lost it on the way out. This class tells those two apart, which is the whole reason it reads the
 * column twice: once straight from the table, and once through the endpoint the screen calls.
 *
 * <p><strong>How it mirrors staging.</strong> Meta is a Mockito {@link MetaWhatsAppClient} that
 * accepts some templates and refuses others, so nothing leaves the building. The service is built by
 * hand around the context's real {@code JdbcTemplate}, secret store and audit service — the reason
 * {@link WhatsAppTestSendIT} gives, so this class has no {@code @MockBean} or {@code @Import} and
 * shares the suite's default context — but it is then wrapped in the application's own transaction
 * interceptor. Without that, a hand-built service would run every statement on its own freshly
 * checked-out connection, and staging's save runs inside one {@code @Transactional} on one
 * connection; a test that differed there could not speak to the defect at all. The first test proves
 * the transaction is really open while Meta is being called.
 *
 * <p><strong>T-168: the second Save, where Meta already held most templates.</strong> A mock client
 * cannot find that defect, because the defect was in the client: it decided what Meta's answer meant.
 * So the T-168 tests use the <em>real</em> {@link MetaWhatsAppClient}, pointed at a JDK
 * {@link HttpServer} on 127.0.0.1 that answers with Meta's error bodies (their sources are in
 * {@link MetaTemplateOutcomeTest}). Nothing reaches Meta.
 *
 * <p>The database is real and the application connects as the unprivileged {@code kms_app} role, so
 * every read and write here passes through {@code tenant_settings}' Row-Level Security policy.
 */
class WhatsAppTemplateSubmissionIT extends AbstractIntegrationTest {

	/** The six Meta refused on staging, less shift_reminder, which T-180 removed. Every other template is accepted. */
	private static final Set<String> REFUSED_ON_STAGING = Set.of(
			"po_delivery", "shift_broadcast", "temple_announcement",
			"temple_communication", "low_stock_digest");

	/**
	 * The eleven staging's second Save, 2026-09-13 01:35:58Z, got "There is already English content
	 * for this template" for.
	 */
	private static final Set<String> ALREADY_HELD_ON_STAGING = Set.of(
			"leave_revoked", "leave_declined", "leave_approved", "staff_schedule_updated", "shift_cancelled",
			"removed_from_shift", "waitlist_promoted", "shift_signup_confirmed", "volunteer_shift_reminder",
			"donation_receipt", "wishlist_sponsorship_converted");

	/** The two Meta re-categorised as MARKETING itself, and refused to register as UTILITY on that save. */
	private static final Set<String> HELD_AS_MARKETING_ON_STAGING = Set.of("donation_thank_you", "wishlist_gift_split");

	private static final String HELD_AS_MARKETING_REASON =
			"Meta holds this message as marketing, which some countries do not deliver.";

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TenantSecretStore secrets;

	@Autowired
	private AuditService auditService;

	@Autowired
	private TenantEmailIdentityService emails;

	@Autowired
	private UserRepository users;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private JdbcTemplate admin;
	private MetaWhatsAppClient meta;
	private MockMvc mvc;
	private UUID govinda;
	private HttpServer metaServer;
	private final AtomicBoolean transactionOpenWhileCallingMeta = new AtomicBoolean();

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		govinda = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Sri Sri Radha Govinda Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-admin-t159', 'Temple Admin', 'admin-t159@example.com', '+919876500091',
						'TEMPLE_ADMIN', 'ACTIVE')
				""", govinda);

		meta = mock(MetaWhatsAppClient.class);
		when(meta.verifyNumber(anyString(), anyString())).thenAnswer(call -> {
			transactionOpenWhileCallingMeta.set(TransactionSynchronizationManager.isActualTransactionActive());
			return "Temple Kitchen (+1 555-010-0159)";
		});
		useMeta(meta);

		TenantContext.set(govinda);
		AuthenticatedUser actor = new AuthenticatedUser(users.findAllByFirebaseUid("uid-admin-t159").stream()
				.filter(account -> govinda.equals(account.getTenantId())).findFirst().orElseThrow());
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(actor, null, actor.getAuthorities()));
	}

	/** Builds the service around this client, in the application's own transaction, behind the controller. */
	private void useMeta(MetaWhatsAppClient client) {
		TenantWhatsAppSettingsService target = new TenantWhatsAppSettingsService(
				jdbc, secrets, auditService, client, "https://kms.example");
		ProxyFactory proxy = new ProxyFactory(target);
		proxy.setProxyTargetClass(true);
		proxy.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
		TenantWhatsAppSettingsService service = (TenantWhatsAppSettingsService) proxy.getProxy();

		mvc = MockMvcBuilders.standaloneSetup(new WhatsAppSettingsController(service, emails))
				.setControllerAdvice(new GlobalExceptionHandler())
				.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
				.setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
				.build();
	}

	@AfterEach
	void tearDown() {
		if (metaServer != null) {
			metaServer.stop(0);
		}
		SecurityContextHolder.clearContext();
		TenantContext.clear();
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM tenant_settings");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	private static final String TOO_MANY_VARIABLES = "This template has too many variables for its length. "
			+ "Reduce the number of variables or increase the message length.";

	/** Meta's own words on staging, 2026-09-12, per template it refused. */
	private static final Map<String, String> META_SAID = Map.of(
			"po_delivery", TOO_MANY_VARIABLES,
			"shift_broadcast", TOO_MANY_VARIABLES,
			"temple_announcement", TOO_MANY_VARIABLES,
			"temple_communication", "The message body can't have more than two consecutive newline characters, "
					+ "only have parameters, or have more than 10 emojis.",
			"low_stock_digest", "Variables can't be at the start or end of the template.");

	private static MetaWhatsAppClient.TemplateSubmission accepted() {
		return new MetaWhatsAppClient.TemplateSubmission(MetaWhatsAppClient.TemplateOutcome.SUBMITTED, null);
	}

	/**
	 * Every registration, answered by name, in both the forms the service calls: the seven-argument one for
	 * a template with no header, and the one with a header for po_delivery (T-200). Stubbing only the first
	 * would leave po_delivery's registration answering null from the mock. The sample upload the header needs
	 * is answered too, with a handle shaped like Meta's.
	 */
	private void metaAnswersRegistrations(org.mockito.stubbing.Answer<MetaWhatsAppClient.TemplateSubmission> answer) {
		when(meta.uploadTemplateSample(anyString(), anyString(), any(), anyString(), anyString())).thenReturn("4::t200-sample");
		when(meta.createTemplate(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
				anyList())).thenAnswer(answer);
		when(meta.createTemplate(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
				anyList(), any())).thenAnswer(answer);
	}

	/** Meta accepting everything except the six it refused on staging, refusing those in its own words. */
	private void metaRefusesWhatItRefusedOnStaging() {
		metaAnswersRegistrations(call -> {
			String name = call.getArgument(2);
			return REFUSED_ON_STAGING.contains(name)
					? new MetaWhatsAppClient.TemplateSubmission(
							MetaWhatsAppClient.TemplateOutcome.REFUSED, META_SAID.get(name))
					: accepted();
		});
	}

	private void metaRefusesEverything() {
		metaAnswersRegistrations(call -> new MetaWhatsAppClient.TemplateSubmission(
				MetaWhatsAppClient.TemplateOutcome.REFUSED, "Something Meta has never said before."));
	}

	private void metaAcceptsEverything() {
		metaAnswersRegistrations(call -> accepted());
	}

	// ---- T-168: the real client against Meta's real answers ------------------------------------------

	/** One HTTP answer from Meta. */
	private record MetaAnswer(int status, String body) {
	}

	private static final MetaAnswer CREATED = new MetaAnswer(200,
			"{\"id\":\"1234567890123456\",\"status\":\"PENDING\",\"category\":\"UTILITY\"}");

	/** Documented code and subcode, staging's sentence. See MetaTemplateOutcomeTest for sources. */
	private static final MetaAnswer ALREADY_EXISTS = new MetaAnswer(400, """
			{"error":{"message":"Invalid parameter","type":"OAuthException","code":100,"error_subcode":2388024,
			"error_user_title":"Content in This Language Already Exists",
			"error_user_msg":"There is already English content for this template. You can create a new template and try again."}}
			""");

	/** Staging's sentence. No subcode: Meta documents none. */
	private static final MetaAnswer HELD_AS_MARKETING = new MetaAnswer(400, """
			{"error":{"message":"Invalid parameter","type":"OAuthException","code":100,
			"error_user_msg":"The category UTILITY doesn't match the one that's already associated with this template, MARKETING."}}
			""");

	private static final MetaAnswer UNKNOWN_REFUSAL = new MetaAnswer(400,
			"{\"error\":{\"message\":\"(#100) Invalid parameter\",\"type\":\"OAuthException\",\"code\":100}}");

	/**
	 * Swaps the mock for the production client, pointed at a local server playing Meta: it describes
	 * the phone number on a GET, and answers each template registration by the name in its JSON body.
	 */
	private void realMetaAnswering(Function<String, MetaAnswer> answerFor) throws Exception {
		metaServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		metaServer.createContext("/", exchange -> {
			byte[] raw = exchange.getRequestBody().readAllBytes();
			String request = new String(raw, StandardCharsets.UTF_8);
			String path = exchange.getRequestURI().getPath();
			received.add(new Received(exchange.getRequestMethod(), path, exchange.getRequestURI().getRawQuery(),
					exchange.getRequestHeaders().getFirst("Authorization"),
					exchange.getRequestHeaders().getFirst("file_offset"), raw));
			// T-200: Meta's Resumable Upload API, as its guide describes it. The session id carries a query of
			// its own, as Meta's does, so the client must follow it without encoding it.
			MetaAnswer answer = "GET".equals(exchange.getRequestMethod())
					? new MetaAnswer(200, "{\"display_phone_number\":\"+1 555-010-0159\",\"verified_name\":\"Temple Kitchen\"}")
					: path.endsWith("/uploads")
							? new MetaAnswer(200, "{\"id\":\"upload:MTphdHRhY2htZW50T200?sig=ARZt200\"}")
					: path.startsWith("/upload:")
							? new MetaAnswer(200, "{\"h\":\"" + SAMPLE_HANDLE + "\"}")
					: answerFor.apply(objectMapper.readTree(request).path("name").asText());
			byte[] out = answer.body().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(answer.status(), out.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(out);
			}
		});
		metaServer.start();
		useMeta(new MetaWhatsAppClient(objectMapper, "http://127.0.0.1:" + metaServer.getAddress().getPort()));
	}

	/** The handle the stub's upload answers with, as Meta's guide shows one. */
	private static final String SAMPLE_HANDLE = "4::YXBwbGljYXRpb24vcGRmT200";

	/** One request the local Meta received. */
	private record Received(String method, String path, String query, String authorization, String fileOffset,
			byte[] body) {
	}

	private final List<Received> received = Collections.synchronizedList(new ArrayList<>());

	/** Meta as staging's second Save found it: six new, eleven already held, two held as marketing. */
	private MetaAnswer asOnStagingsSecondSave(String name) {
		if (HELD_AS_MARKETING_ON_STAGING.contains(name)) {
			return HELD_AS_MARKETING;
		}
		return ALREADY_HELD_ON_STAGING.contains(name) ? ALREADY_EXISTS : CREATED;
	}

	/** The refused list straight from tenant_settings, as the app role sees it, as name → reason. */
	private Map<String, String> storedRefusals() throws Exception {
		String stored = jdbc.queryForObject("SELECT whatsapp_refused_templates::text FROM tenant_settings", String.class);
		Map<String, String> byName = new java.util.TreeMap<>();
		objectMapper.readTree(stored).forEach(e -> byName.put(e.get("name").asText(), e.get("reason").asText()));
		return byName;
	}

	/** The same list as name → kind, with a missing kind read as the text "absent". */
	private Map<String, String> storedKinds() throws Exception {
		String stored = jdbc.queryForObject("SELECT whatsapp_refused_templates::text FROM tenant_settings", String.class);
		Map<String, String> byName = new java.util.TreeMap<>();
		objectMapper.readTree(stored).forEach(e -> byName.put(e.get("name").asText(),
				e.hasNonNull("kind") ? e.get("kind").asText() : "absent"));
		return byName;
	}

	/**
	 * Since T-169a only a first connection's Save sends templates, so every "later send" in this class
	 * is the Reload button's.
	 */
	private ResultActions reloadTemplates() throws Exception {
		return mvc.perform(post("/api/v1/settings/whatsapp/templates/reload"));
	}

	/** The App ID every save in this class carries unless a test says otherwise (T-200). */
	private static final String APP_ID = "1234567890123456";

	private ResultActions saveSettings() throws Exception {
		return saveSettings(APP_ID);
	}

	/** A save with this App ID, or with no App ID field at all when it is null. */
	private ResultActions saveSettings(String appId) throws Exception {
		Map<String, Object> body = new java.util.LinkedHashMap<>(Map.of(
				"phoneNumberId", "phone-govinda", "wabaId", "waba-govinda",
				"accessToken", "token-govinda", "appSecret", "secret-govinda"));
		if (appId != null) {
			body.put("appId", appId);
		}
		return mvc.perform(put("/api/v1/settings/whatsapp")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)));
	}

	/**
	 * The column, straight from the table, as the application's own unprivileged role sees it — so
	 * through the RLS policy, with the tenant taken from the connection and no WHERE of our own.
	 */
	private Object storedSubmittedAt() {
		return jdbc.queryForList("SELECT whatsapp_templates_submitted_at FROM tenant_settings")
				.stream().findFirst().map(row -> row.get("whatsapp_templates_submitted_at")).orElse(null);
	}

	@Test
	@DisplayName("after a save where Meta refuses some templates, the date is in the table AND on the screen's answer")
	void submittedDateIsStoredAndReturned() throws Exception {
		metaRefusesWhatItRefusedOnStaging();

		saveSettings()
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.displayNumber").value("Temple Kitchen (+1 555-010-0159)"));

		assertThat(transactionOpenWhileCallingMeta.get())
				.as("the save ran inside one transaction, as it does on staging")
				.isTrue();
		assertThat(storedSubmittedAt())
				.as("whatsapp_templates_submitted_at read straight from tenant_settings")
				.isNotNull();

		// And the question staging actually answered wrongly: what a signed-in GET returns a moment later.
		mvc.perform(get("/api/v1/settings/whatsapp"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.phoneNumberId").value("phone-govinda"))
				.andExpect(jsonPath("$.templatesSubmittedAt").value(notNullValue()))
				.andExpect(jsonPath("$.verifiedAt").value(notNullValue()));
	}

	/**
	 * The mechanism, pinned so the explanation is a tested claim and not a comment.
	 *
	 * <p>{@code queryForMap} asks the PostgreSQL driver for each column with a plain
	 * {@code getObject(int)}, and for {@code timestamptz} that answers {@code java.sql.Timestamp} —
	 * never {@code OffsetDateTime}, which the driver only produces when asked for it by class. A read
	 * that accepted only {@code OffsetDateTime} would turn every stored date into null on the way to
	 * the screen, and this is the fact that makes that possible.
	 */
	@Test
	@DisplayName("a timestamptz read without naming a type comes back as java.sql.Timestamp")
	void theDriverAnswersTimestampWhenNotToldOtherwise() throws Exception {
		metaRefusesWhatItRefusedOnStaging();
		saveSettings().andExpect(status().isOk());

		Object raw = jdbc.queryForMap("SELECT whatsapp_templates_submitted_at FROM tenant_settings")
				.get("whatsapp_templates_submitted_at");
		assertThat(raw).isInstanceOf(java.sql.Timestamp.class);
	}

	/**
	 * What the column means, decided from V55's own comment: "this records that we asked, never that
	 * they said yes". A save where Meta accepted or already held at least one template is a save where
	 * something was registered; a save where every one was refused registered nothing, and a date
	 * saying the templates "went to Meta" would be untrue.
	 */
	@Test
	@DisplayName("a save where Meta refuses every template leaves the date empty")
	void everythingRefusedLeavesNoDate() throws Exception {
		metaRefusesEverything();

		saveSettings().andExpect(status().isOk());

		assertThat(storedSubmittedAt()).isNull();
		mvc.perform(get("/api/v1/settings/whatsapp"))
				.andExpect(jsonPath("$.templatesSubmittedAt").doesNotExist());
	}

	// ---- Part 4: which templates were refused, and why, where the screen can show it (V128) ----------

	@Test
	@DisplayName("each template Meta refused is stored on the temple's row with a plain reason, none of Meta's words")
	void refusalsAreStoredInPlainWords() throws Exception {
		metaRefusesWhatItRefusedOnStaging();

		saveSettings().andExpect(status().isOk());

		Map<String, String> stored = storedRefusals();
		assertThat(stored.keySet()).containsExactlyInAnyOrderElementsOf(REFUSED_ON_STAGING);
		assertThat(stored.get("po_delivery")).startsWith("Meta found too little fixed wording");
		assertThat(stored.get("low_stock_digest")).startsWith("Meta will not accept a message that begins or ends");
		assertThat(stored.get("temple_communication")).startsWith("Meta found no fixed wording of its own");
		stored.values().forEach(reason -> assertThat(reason)
				.as("a sentence for an administrator, not Meta's developer text")
				.doesNotContainIgnoringCase("variables").doesNotContainIgnoringCase("template")
				.doesNotContainIgnoringCase("newline").doesNotContainIgnoringCase("parameters"));
		assertThat(storedKinds()).allSatisfy((name, kind) -> assertThat(kind).isEqualTo("REFUSED"));

		// And it reaches the answer the screen reads.
		mvc.perform(get("/api/v1/settings/whatsapp"))
				.andExpect(jsonPath("$.refusedTemplates.length()").value(5))
				.andExpect(jsonPath("$.refusedTemplates[?(@.name == 'po_delivery')].reason")
						.value(org.hamcrest.Matchers.hasItem(stored.get("po_delivery"))));
	}

	@Test
	@DisplayName("a refusal Meta words in a way we have not seen gets a next step, not Meta's sentence")
	void anUnknownRefusalGetsANextStep() throws Exception {
		metaRefusesEverything();

		saveSettings().andExpect(status().isOk());

		assertThat(storedRefusals()).hasSize(NotificationTemplate.values().length)
				.allSatisfy((name, reason) -> assertThat(reason)
						.startsWith("Meta did not accept this message. Try again with the templates button in the WhatsApp section of Settings")
						.doesNotContain("never said before"));
	}

	@Test
	@DisplayName("a template Meta could not be asked about is kept too, with its own reason")
	void anUnreachableMetaIsRecorded() throws Exception {
		metaAnswersRegistrations(call -> {
			if ("shift_cancelled".equals(call.getArgument(2))) {
				throw new MetaWhatsAppClient.WhatsAppCredentialsRejected("Could not reach Meta just now.");
			}
			return accepted();
		});

		saveSettings().andExpect(status().isOk());

		assertThat(storedRefusals()).containsOnlyKeys("shift_cancelled");
		assertThat(storedRefusals().get("shift_cancelled")).startsWith("Meta could not be reached");
		assertThat(storedKinds()).containsEntry("shift_cancelled", "NOT_REACHED");
	}

	/**
	 * The list is a snapshot of the last send, not a history. Since T-169a the later send is a Reload,
	 * because a second Save sends nothing. Note for a negative control: with the
	 * write removed this test passes vacuously, because it ends by asserting an absence and a list
	 * that was never written is also empty. {@link #refusalsAreStoredInPlainWords} is the one that
	 * proves the write, and {@link #stagingsFalseRefusalsClearOnTheNextSave} proves the replacement
	 * with a list that is not empty afterwards.
	 */
	@Test
	@DisplayName("a later Reload where Meta accepts everything clears the list")
	void aCleanSaveClearsTheList() throws Exception {
		metaRefusesWhatItRefusedOnStaging();
		saveSettings().andExpect(status().isOk());

		org.mockito.Mockito.reset(meta);
		when(meta.verifyNumber(anyString(), anyString())).thenReturn("Temple Kitchen (+1 555-010-0159)");
		metaAcceptsEverything();
		reloadTemplates().andExpect(status().isOk());

		assertThat(storedRefusals()).isEmpty();
		mvc.perform(get("/api/v1/settings/whatsapp"))
				.andExpect(jsonPath("$.refusedTemplates").isArray())
				.andExpect(jsonPath("$.refusedTemplates.length()").value(0));
	}

	// ---- T-168: Meta already holding templates is not a refusal ---------------------------------------

	/**
	 * The staging data in this class is a claim about the code's templates, so it is checked against
	 * them: the eleven, the two, and what is left — which must be the six T-159 reworded, less the one T-180 removed, plus the
	 * renamed connection check, the seven staging's log counted as submitted, plus the two T-184 added after staging's save.
	 */
	@Test
	@DisplayName("the staging template names used here are the application's own, and account for all twenty-one")
	void stagingNamesAreReal() {
		Set<String> all = Arrays.stream(NotificationTemplate.values())
				.map(NotificationTemplate::whatsappTemplateName).collect(Collectors.toSet());
		assertThat(all).hasSize(21).containsAll(ALREADY_HELD_ON_STAGING).containsAll(HELD_AS_MARKETING_ON_STAGING);

		Set<String> rest = new HashSet<>(all);
		rest.removeAll(ALREADY_HELD_ON_STAGING);
		rest.removeAll(HELD_AS_MARKETING_ON_STAGING);
		Set<String> expected = new HashSet<>(REFUSED_ON_STAGING);
		expected.add("whatsapp_connection_check");
		// T-184: not on staging when it saved, so neither held nor refused there.
		expected.add("leave_withdrawn");
		expected.add("leave_withdrawn_notice");
		assertThat(rest).isEqualTo(expected);
	}

	@Test
	@DisplayName("staging's second save: no false refusals, the two held as marketing say so truthfully, and the date is set")
	void stagingsSecondSaveStoresNoFalseRefusals() throws Exception {
		realMetaAnswering(this::asOnStagingsSecondSave);

		saveSettings().andExpect(status().isOk());

		assertThat(storedRefusals())
				.as("only the two Meta holds as marketing; none of the eleven it already held")
				.containsOnlyKeys(HELD_AS_MARKETING_ON_STAGING)
				.allSatisfy((name, reason) -> assertThat(reason).isEqualTo(HELD_AS_MARKETING_REASON));
		assertThat(storedKinds()).allSatisfy((name, kind) -> assertThat(kind).isEqualTo("HELD_UNDER_ANOTHER_CATEGORY"));
		assertThat(storedSubmittedAt()).isNotNull();

		// DESIGN_SYSTEM §9: twelve words or fewer, no semicolon, sentence case.
		assertThat(HELD_AS_MARKETING_REASON.split("\\s+")).hasSizeLessThanOrEqualTo(12);
		assertThat(HELD_AS_MARKETING_REASON).doesNotContain(";").startsWith("Meta holds");

		mvc.perform(get("/api/v1/settings/whatsapp"))
				.andExpect(jsonPath("$.templatesSubmittedAt").value(notNullValue()))
				.andExpect(jsonPath("$.refusedTemplates.length()").value(2))
				.andExpect(jsonPath("$.refusedTemplates[0].kind").value("HELD_UNDER_ANOTHER_CATEGORY"))
				.andExpect(jsonPath("$.refusedTemplates[1].kind").value("HELD_UNDER_ANOTHER_CATEGORY"))
				.andExpect(jsonPath("$.refusedTemplates[0].reason").value(HELD_AS_MARKETING_REASON));
	}

	/** Every temple that saves twice: Meta holds all nineteen, and that is a clean result. */
	@Test
	@DisplayName("a save where Meta already holds every template stores nothing and sets the date")
	void everythingAlreadyHeldIsClean() throws Exception {
		realMetaAnswering(name -> ALREADY_EXISTS);

		saveSettings().andExpect(status().isOk());

		assertThat(storedRefusals()).isEmpty();
		assertThat(storedSubmittedAt()).isNotNull();
	}

	/**
	 * The date decision for the new outcome, isolated: the only templates Meta holds are the two it
	 * holds as marketing, and everything else is refused. They count, so the date is set.
	 */
	@Test
	@DisplayName("a template Meta holds under another category counts toward the date on its own")
	void heldUnderAnotherCategoryCountsTowardTheDate() throws Exception {
		realMetaAnswering(name -> HELD_AS_MARKETING_ON_STAGING.contains(name) ? HELD_AS_MARKETING : UNKNOWN_REFUSAL);

		saveSettings().andExpect(status().isOk());

		assertThat(storedSubmittedAt()).isNotNull();
		Map<String, String> kinds = storedKinds();
		assertThat(kinds).hasSize(21);
		assertThat(kinds.entrySet().stream().filter(e -> e.getValue().equals("HELD_UNDER_ANOTHER_CATEGORY"))
				.map(Map.Entry::getKey)).containsExactlyInAnyOrderElementsOf(HELD_AS_MARKETING_ON_STAGING);
		assertThat(kinds.values().stream().filter("REFUSED"::equals)).hasSize(19);
	}

	/**
	 * What will actually happen on staging: its row holds thirteen false refusals written by T-159's
	 * code, with no kind. They must read back (as refusals, which is what they were written as), and
	 * the next send must replace them outright with the truth — not add to them, not keep them. Since
	 * T-169a that send is a Reload. The stale text below still says "Press Save", because that is what
	 * T-159 wrote and staging's row holds.
	 */
	@Test
	@DisplayName("staging's thirteen false refusals read back, then clear on the next Reload")
	void stagingsFalseRefusalsClearOnTheNextSave() throws Exception {
		Set<String> falselyRefused = new HashSet<>(ALREADY_HELD_ON_STAGING);
		falselyRefused.addAll(HELD_AS_MARKETING_ON_STAGING);
		String t159Reason = "Meta did not accept this message. Press Save to try again, and if it is refused again, "
				+ "report it with the message name shown here.";
		String staleList = objectMapper.writeValueAsString(falselyRefused.stream()
				.map(name -> Map.of("name", name, "reason", t159Reason)).toList());
		// The row is made by a real save, as staging's was: tenant_settings_whatsapp_shape refuses a
		// hand-made row missing the other WhatsApp columns. Then T-159's stale list is written over it.
		metaAcceptsEverything();
		saveSettings().andExpect(status().isOk());
		jdbc.update("""
				UPDATE tenant_settings SET whatsapp_refused_templates = ?::jsonb
				WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""", staleList);
		assertThat(storedKinds()).hasSize(13).allSatisfy((name, kind) -> assertThat(kind).isEqualTo("absent"));

		realMetaAnswering(this::asOnStagingsSecondSave);
		mvc.perform(get("/api/v1/settings/whatsapp"))
				.andExpect(jsonPath("$.refusedTemplates.length()").value(13))
				.andExpect(jsonPath("$.refusedTemplates[0].kind").value("REFUSED"));

		reloadTemplates().andExpect(status().isOk());

		assertThat(storedRefusals()).containsOnlyKeys(HELD_AS_MARKETING_ON_STAGING);
		mvc.perform(get("/api/v1/settings/whatsapp"))
				.andExpect(jsonPath("$.refusedTemplates.length()").value(2));
	}

	// ---- T-200: the purchase order registered with its PDF header ----------------------------------------

	@Test
	@DisplayName("po_delivery is registered with a DOCUMENT header whose example handle came from Meta's upload to the App ID; no other template has a header")
	void thePurchaseOrderIsRegisteredWithADocumentHeader() throws Exception {
		realMetaAnswering(name -> CREATED);

		saveSettings().andExpect(status().isOk());

		assertThat(storedRefusals()).isEmpty();
		// One upload session, addressed to the App ID, and the bytes that followed it.
		List<Received> uploads = received.stream().filter(r -> r.path().endsWith("/uploads")).toList();
		assertThat(uploads).hasSize(1);
		Received start = uploads.get(0);
		assertThat(start.method()).isEqualTo("POST");
		assertThat(start.path()).isEqualTo("/" + APP_ID + "/uploads");
		byte[] sample = TenantWhatsAppSettingsService.samplePurchaseOrderPdf();
		assertThat(start.query()).isEqualTo("file_name=sample-purchase-order.pdf&file_length=" + sample.length
				+ "&file_type=application%2Fpdf");
		assertThat(start.authorization()).as("the stored token, as Meta's guide asks, never in the URL")
				.isEqualTo("OAuth token-govinda");
		assertThat(start.query()).doesNotContain("token-govinda");

		List<Received> bytes = received.stream().filter(r -> r.path().startsWith("/upload:")).toList();
		assertThat(bytes).hasSize(1);
		assertThat(bytes.get(0).path()).isEqualTo("/upload:MTphdHRhY2htZW50T200");
		assertThat(bytes.get(0).query()).as("Meta's session id followed exactly, its own query included").isEqualTo("sig=ARZt200");
		assertThat(bytes.get(0).fileOffset()).isEqualTo("0");
		assertThat(bytes.get(0).authorization()).isEqualTo("OAuth token-govinda");
		assertThat(bytes.get(0).body()).isEqualTo(sample);
		assertThat(new String(sample, StandardCharsets.US_ASCII)).startsWith("%PDF-1.4").endsWith("%%EOF\n");

		// The registration itself.
		Map<String, com.fasterxml.jackson.databind.JsonNode> creates = new java.util.TreeMap<>();
		for (Received r : received) {
			if (r.path().endsWith("/message_templates") && "POST".equals(r.method())) {
				com.fasterxml.jackson.databind.JsonNode body = objectMapper.readTree(r.body());
				creates.put(body.path("name").asText(), body);
			}
		}
		assertThat(creates).hasSize(NotificationTemplate.values().length);
		com.fasterxml.jackson.databind.JsonNode components = creates.get("po_delivery").path("components");
		assertThat(components).hasSize(2);
		assertThat(components.get(0).path("type").asText()).isEqualTo("HEADER");
		assertThat(components.get(0).path("format").asText()).isEqualTo("DOCUMENT");
		assertThat(components.get(0).path("example").path("header_handle")).hasSize(1);
		assertThat(components.get(0).path("example").path("header_handle").get(0).asText()).isEqualTo(SAMPLE_HANDLE);
		assertThat(components.get(1).path("type").asText()).isEqualTo("BODY");
		assertThat(components.get(1).path("text").asText())
				.as("the approved wording, unchanged").isEqualTo(NotificationTemplate.PO_DELIVERY.whatsappBodyText());
		creates.forEach((name, body) -> {
			if (!"po_delivery".equals(name)) {
				assertThat(body.path("components")).as(name).hasSize(1);
				assertThat(body.path("components").get(0).path("type").asText()).as(name).isEqualTo("BODY");
			}
		});
		// Upload first, then registration: the handle must exist before it is named.
		assertThat(received.indexOf(bytes.get(0))).isLessThan(received.stream()
				.filter(r -> r.path().endsWith("/message_templates") && new String(r.body(), StandardCharsets.UTF_8).contains("po_delivery"))
				.findFirst().map(received::indexOf).orElseThrow());

		// And the App ID comes back on the screen's answer.
		mvc.perform(get("/api/v1/settings/whatsapp")).andExpect(jsonPath("$.appId").value(APP_ID));
	}

	@Test
	@DisplayName("a temple with no App ID gets a stored reason for po_delivery naming the App ID box, and nothing is uploaded or registered for it")
	void noAppIdStoresAReasonNamingTheBoxAndUploadsNothing() throws Exception {
		realMetaAnswering(name -> CREATED);

		saveSettings(null).andExpect(status().isOk());

		assertThat(storedRefusals()).containsOnlyKeys("po_delivery");
		assertThat(storedRefusals().get("po_delivery"))
				.isEqualTo(TenantWhatsAppSettingsService.NEEDS_APP_ID)
				.contains("App ID box in the WhatsApp section of Settings")
				.contains(TenantWhatsAppSettingsService.TEMPLATES_BUTTON);
		assertThat(storedKinds()).containsEntry("po_delivery", "REFUSED");
		assertThat(received).as("no upload of any kind").noneMatch(r -> r.path().contains("upload"));
		assertThat(received).as("po_delivery not registered")
				.noneMatch(r -> new String(r.body(), StandardCharsets.UTF_8).contains("\"po_delivery\""));
		assertThat(received.stream().filter(r -> r.path().endsWith("/message_templates")).count())
				.as("every other template still registered").isEqualTo(NotificationTemplate.values().length - 1);

		mvc.perform(get("/api/v1/settings/whatsapp"))
				.andExpect(jsonPath("$.appId").doesNotExist())
				.andExpect(jsonPath("$.templatesPending.refused").value(1));
	}

	@Test
	@DisplayName("the App ID is saved, kept by a save that does not send it, cleared by a blank one, and refused when it is not digits")
	void theAppIdIsSavedKeptClearedAndChecked() throws Exception {
		metaAcceptsEverything();

		saveSettings().andExpect(status().isOk()).andExpect(jsonPath("$.appId").value(APP_ID));
		assertThat(jdbc.queryForObject("SELECT whatsapp_app_id FROM tenant_settings", String.class)).isEqualTo(APP_ID);

		saveSettings(null).andExpect(status().isOk()).andExpect(jsonPath("$.appId").value(APP_ID));

		saveSettings("not-digits").andExpect(status().isBadRequest());
		assertThat(jdbc.queryForObject("SELECT whatsapp_app_id FROM tenant_settings", String.class)).isEqualTo(APP_ID);

		saveSettings("").andExpect(status().isOk()).andExpect(jsonPath("$.appId").doesNotExist());
		assertThat(jdbc.queryForObject("SELECT whatsapp_app_id FROM tenant_settings", String.class)).isNull();
	}
}
