package org.iskcon.kms.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ErrorCode;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * The Reload WhatsApp Templates button's backend, and Save sending templates on a first connection
 * only (T-169a).
 *
 * <p>Rajeev, 2026-09-13: <em>"For Watts App specifically, we need a new button Reload Wattsapp
 * Templates. Click on that to reload watts app templates. We can show a date when the templates were
 * uploaded last."</em> Approved with it: the first connection sends the templates automatically,
 * after that Save only saves, and Reload must actually update changed wording.
 *
 * <p><strong>Meta is a fake at the HTTP level, and a stateful one.</strong> The production
 * {@link MetaWhatsAppClient} is pointed at a JDK {@link HttpServer} on 127.0.0.1, as in
 * {@link WhatsAppTemplateSubmissionIT} and {@link MetaTemplateOutcomeTest}, so the requests going out,
 * Meta's JSON coming back and every decision the client and the service make are all real. Unlike those
 * two, this fake keeps what it holds: a registration of a name it holds answers "already exists", a
 * lookup lists what it holds, and an edit changes it. Reload's whole job is to bring what Meta holds
 * into line with the code, so a fake that answered each call alone could not tell a Reload that worked
 * from one that only made the right calls. It also counts every call by kind, which is how "a second
 * Save sends nothing" is asserted: at the wire, not at a mock.
 *
 * <p>Where the fake's answers come from: the error envelopes and the already-exists and category
 * mismatch bodies are T-168's, sourced in {@link MetaTemplateOutcomeTest}. The lookup's shape (a
 * {@code data} array of templates with {@code id}, {@code name}, {@code language}, {@code status},
 * {@code category}, {@code components}) is Meta's Graph API reference for
 * {@code GET /{WABA_ID}/message_templates}. The edit answer {@code {"success": true}}, and the
 * 2388039 title and sentence, are Meta's template management and error codes pages. All three URLs are
 * cited in {@link MetaWhatsAppClient}. The fake's name filter matches <em>part</em> of a name on
 * purpose, because Meta's reference does not say it matches whole names, and the client must still
 * pick {@code shift_reminder} rather than {@code volunteer_shift_reminder}.
 *
 * <p>Nothing leaves the building. The service is built by hand and wrapped in the application's own
 * transaction interceptor, for the reason {@link WhatsAppTemplateSubmissionIT} gives, and this class
 * has no {@code @MockBean}, {@code @Import} or {@code @TestPropertySource}, so it shares the suite's
 * default context. The database is real and read as the unprivileged {@code kms_app} role, through
 * {@code tenant_settings}' Row-Level Security policy.
 */
class WhatsAppTemplateReloadIT extends AbstractIntegrationTest {

	private static final OffsetDateTime LONG_AGO = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

	/**
	 * T-178: every Reload ends by taking the temple's stored copy of Meta's status, which is T-173's
	 * comparison, one lookup per template. So a Reload's lookups are its own plus these.
	 */
	private static final int STATUS_COPY_LOOKUPS = NotificationTemplate.values().length;

	/** T-159's own record of shift_reminder's wording before it was reworded. */
	private static final String OLD_SHIFT_REMINDER = "Reminder: your {{1}} shift at {{2}} is on {{3}} at {{4}}.";

	/** The six T-159 reworded, which Meta on staging already holds in their new wording. */
	private static final Set<String> REWORDED_BY_T159 = Set.of(
			"shift_reminder", "po_delivery", "shift_broadcast", "temple_announcement",
			"temple_communication", "low_stock_digest");

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
	private UUID govinda;
	private FakeMeta meta;
	private TenantWhatsAppSettingsService service;
	private MockMvc mvc;

	@BeforeEach
	void setUp() throws Exception {
		admin = new JdbcTemplate(adminDataSource());
		govinda = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Sri Sri Radha Govinda Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-admin-t169a', 'Temple Admin', 'admin-t169a@example.com', '+919876500169',
						'TEMPLE_ADMIN', 'ACTIVE'),
					   (?, 'uid-volunteer-t169a', 'A Volunteer', 'volunteer-t169a@example.com', '+919876500170',
						'VOLUNTEER', 'ACTIVE')
				""", govinda, govinda);

		meta = new FakeMeta(objectMapper);
		TenantWhatsAppSettingsService target = new TenantWhatsAppSettingsService(jdbc, secrets, auditService,
				new MetaWhatsAppClient(objectMapper, meta.url()), "https://kms.example");
		ProxyFactory proxy = new ProxyFactory(target);
		proxy.setProxyTargetClass(true);
		proxy.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
		service = (TenantWhatsAppSettingsService) proxy.getProxy();
		mvc = mvcFor(new WhatsAppSettingsController(service, emails));

		TenantContext.set(govinda);
		signInAs("uid-admin-t169a");
	}

	@AfterEach
	void tearDown() {
		meta.stop();
		SecurityContextHolder.clearContext();
		TenantContext.clear();
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM tenant_settings");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	private MockMvc mvcFor(WhatsAppSettingsController controller) {
		return MockMvcBuilders.standaloneSetup(controller)
				.setControllerAdvice(new GlobalExceptionHandler())
				.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
				.setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
				.build();
	}

	private void signInAs(String firebaseUid) {
		AuthenticatedUser actor = new AuthenticatedUser(users.findByFirebaseUid(firebaseUid).orElseThrow());
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(actor, null, actor.getAuthorities()));
	}

	// ---- requests ------------------------------------------------------------------------------------

	private ResultActions save(String phoneNumberId, String wabaId) throws Exception {
		return mvc.perform(put("/api/v1/settings/whatsapp")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
						"phoneNumberId", phoneNumberId, "wabaId", wabaId,
						"accessToken", "token-govinda", "appSecret", "secret-govinda"))));
	}

	private ResultActions save() throws Exception {
		return save("phone-govinda", "waba-govinda");
	}

	private ResultActions reload() throws Exception {
		return mvc.perform(post("/api/v1/settings/whatsapp/templates/reload"));
	}

	private JsonNode view() throws Exception {
		return objectMapper.readTree(mvc.perform(get("/api/v1/settings/whatsapp"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}

	// ---- the row, as the app role sees it ------------------------------------------------------------

	/** name -> stored fingerprint, with a JSON null kept as the text "null" so it can be asserted. */
	private Map<String, String> storedFingerprints() throws Exception {
		String stored = jdbc.queryForObject(
				"SELECT whatsapp_template_fingerprints::text FROM tenant_settings", String.class);
		Map<String, String> byName = new TreeMap<>();
		objectMapper.readTree(stored).fields().forEachRemaining(e ->
				byName.put(e.getKey(), e.getValue().isNull() ? "null" : e.getValue().asText()));
		return byName;
	}

	private Map<String, JsonNode> storedList() throws Exception {
		String stored = jdbc.queryForObject("SELECT whatsapp_refused_templates::text FROM tenant_settings", String.class);
		Map<String, JsonNode> byName = new TreeMap<>();
		objectMapper.readTree(stored).forEach(e -> byName.put(e.get("name").asText(), e));
		return byName;
	}

	private void setStoredFingerprint(String name, String value) {
		jdbc.update("""
				UPDATE tenant_settings
				SET whatsapp_template_fingerprints = jsonb_set(whatsapp_template_fingerprints, ARRAY[?], to_jsonb(?::text))
				WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""", name, value);
	}

	private void removeStoredFingerprint(String name) {
		jdbc.update("""
				UPDATE tenant_settings SET whatsapp_template_fingerprints = whatsapp_template_fingerprints - ?
				WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""", name);
	}

	private OffsetDateTime submittedAt() {
		return jdbc.queryForObject("SELECT whatsapp_templates_submitted_at FROM tenant_settings", OffsetDateTime.class);
	}

	private static Map<String, String> everyFingerprintAsReleased() {
		Map<String, String> byName = new TreeMap<>();
		for (NotificationTemplate template : NotificationTemplate.values()) {
			byName.put(template.whatsappTemplateName(), template.whatsappFingerprint("en"));
		}
		return byName;
	}

	private static NotificationTemplate template(String name) {
		return Arrays.stream(NotificationTemplate.values())
				.filter(t -> t.whatsappTemplateName().equals(name)).findFirst().orElseThrow();
	}

	private static Set<String> fieldNames(JsonNode node) {
		Set<String> names = new HashSet<>();
		node.fieldNames().forEachRemaining(names::add);
		return names;
	}

	/**
	 * A temple exactly as South Bengaluru is on staging when V129 deploys: connected, templates
	 * submitted and dated, Meta holding all twenty in this release's wording, and no fingerprints or
	 * account recorded, because the columns did not exist when it sent them.
	 */
	private void aTempleThatSentBeforeV129() throws Exception {
		meta.holdEveryTemplateAsReleased();
		save().andExpect(status().isOk());
		jdbc.update("""
				UPDATE tenant_settings SET whatsapp_template_fingerprints = '{}'::jsonb,
					whatsapp_templates_sent_waba_id = NULL, whatsapp_templates_sent_phone_number_id = NULL
				WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""");
		assertThat(storedFingerprints()).isEmpty();
		assertThat(submittedAt()).isNotNull();
		meta.resetCounts();
	}

	// ---- the view's shape ----------------------------------------------------------------------------

	@Test
	@DisplayName("the view carries refusedTemplates and templatesPending in exactly the shape the screen reads")
	void theViewHasTheContractShape() throws Exception {
		meta.refuseCreating("shift_reminder", FakeMeta.TOO_MANY_VARIABLES);
		meta.hold("donation_thank_you", "MARKETING", "APPROVED", template("donation_thank_you").whatsappBodyText());

		save().andExpect(status().isOk());
		JsonNode view = view();

		// Keys, then values: a missing field and a zero read alike to a matcher that only checks values.
		assertThat(fieldNames(view)).contains("refusedTemplates", "templatesPending");
		JsonNode pending = view.get("templatesPending");
		assertThat(fieldNames(pending)).containsExactlyInAnyOrder("changed", "refused", "accountChanged");
		assertThat(pending.get("changed").isInt()).isTrue();
		assertThat(pending.get("refused").isInt()).isTrue();
		assertThat(pending.get("accountChanged").isBoolean()).isTrue();

		JsonNode refused = view.get("refusedTemplates");
		assertThat(refused.isArray()).isTrue();
		assertThat(refused).hasSize(2);
		refused.forEach(entry -> assertThat(fieldNames(entry)).containsExactlyInAnyOrder("name", "reason", "kind"));
		Map<String, String> kinds = new TreeMap<>();
		refused.forEach(entry -> kinds.put(entry.get("name").asText(), entry.get("kind").asText()));
		assertThat(kinds).containsExactlyInAnyOrderEntriesOf(Map.of(
				"shift_reminder", "REFUSED", "donation_thank_you", "HELD_UNDER_ANOTHER_CATEGORY"));

		// One REFUSED counts. The one Meta holds as marketing does not: no press of Reload can move it.
		assertThat(pending.get("refused").asInt()).isEqualTo(1);
		assertThat(pending.get("changed").asInt()).isZero();
		assertThat(pending.get("accountChanged").asBoolean()).isFalse();
	}

	// ---- Save: the first connection sends, and never again -------------------------------------------

	@Test
	@DisplayName("a first connection sends every template to Meta and records what Meta now holds, so nothing is waiting")
	void aFirstConnectionSendsAndRecords() throws Exception {
		save().andExpect(status().isOk());

		assertThat(meta.creates.get()).isEqualTo(NotificationTemplate.values().length);
		assertThat(meta.lookups.get()).isZero();
		assertThat(meta.edits.get()).isZero();
		assertThat(storedFingerprints()).isEqualTo(everyFingerprintAsReleased());
		assertThat(jdbc.queryForObject("SELECT whatsapp_templates_sent_waba_id FROM tenant_settings", String.class))
				.isEqualTo("waba-govinda");

		mvc.perform(get("/api/v1/settings/whatsapp"))
				.andExpect(jsonPath("$.templatesSubmittedAt").value(notNullValue()))
				.andExpect(jsonPath("$.templatesPending.changed").value(0))
				.andExpect(jsonPath("$.templatesPending.refused").value(0))
				.andExpect(jsonPath("$.templatesPending.accountChanged").value(false));
	}

	/**
	 * The rule Rajeev approved, counted at the wire. The first Save is asserted to send, in the same
	 * test, so the zero afterwards cannot be a fake that never counted anything.
	 */
	@Test
	@DisplayName("a second Save sends nothing to Meta, counted at the HTTP stub, while the first connection did")
	void aSecondSaveSendsNothing() throws Exception {
		save().andExpect(status().isOk());
		assertThat(meta.creates.get()).as("the first connection sends").isEqualTo(NotificationTemplate.values().length);

		meta.resetCounts();
		save().andExpect(status().isOk());

		assertThat(meta.phoneChecks.get()).as("the second Save still checks the credentials").isEqualTo(1);
		assertThat(meta.creates.get()).as("registrations on the second Save").isZero();
		assertThat(meta.lookups.get()).as("lookups on the second Save").isZero();
		assertThat(meta.edits.get()).as("edits on the second Save").isZero();
	}

	// ---- templatesPending ----------------------------------------------------------------------------

	@Test
	@DisplayName("a recorded fingerprint that differs from this release counts as changed, and so does a template new since")
	void aChangedFingerprintCountsAsChanged() throws Exception {
		save().andExpect(status().isOk());
		setStoredFingerprint("shift_reminder", "sha256:the-wording-before-this-release");
		setStoredFingerprint("po_delivery", "sha256:the-wording-before-this-release");

		mvc.perform(get("/api/v1/settings/whatsapp"))
				.andExpect(jsonPath("$.templatesPending.changed").value(2))
				.andExpect(jsonPath("$.templatesPending.refused").value(0));

		// No key at all, on a temple that has fingerprints: the app did not have it when it last sent.
		removeStoredFingerprint("low_stock_digest");
		mvc.perform(get("/api/v1/settings/whatsapp"))
				.andExpect(jsonPath("$.templatesPending.changed").value(3));
	}

	/**
	 * The hard case, decided: a temple that sent templates before V129 has no fingerprints, and an
	 * absent record means unknown, not changed. The Save afterwards proves the same temple is not a
	 * "first connection" either, and the Reload proves the unknown is resolved by asking Meta, without
	 * rewording anything Meta already holds correctly.
	 */
	@Test
	@DisplayName("a temple that sent before V129 is claimed to have nothing changed; its Save sends nothing; its first Reload records all twenty and edits none")
	void aTempleThatSentBeforeV129ClaimsNothing() throws Exception {
		aTempleThatSentBeforeV129();

		JsonNode pending = view().get("templatesPending");
		assertThat(pending.get("changed").asInt()).as("not 20, and not the six T-159 reworded").isZero();
		assertThat(pending.get("refused").asInt()).isZero();
		assertThat(pending.get("accountChanged").asBoolean()).isFalse();

		save().andExpect(status().isOk());
		assertThat(meta.creates.get()).as("its Save is not a first connection").isZero();

		reload().andExpect(status().isOk());
		assertThat(meta.creates.get()).isEqualTo(NotificationTemplate.values().length);
		assertThat(meta.lookups.get()).as("every held template compared, since none was known").isEqualTo(20 + STATUS_COPY_LOOKUPS);
		assertThat(meta.edits.get()).as("Meta already holds this release's wording").isZero();
		assertThat(storedFingerprints()).isEqualTo(everyFingerprintAsReleased());
		assertThat(view().get("templatesPending").toString())
				.isEqualTo("{\"changed\":0,\"refused\":0,\"accountChanged\":false}");
	}

	@Test
	@DisplayName("on a temple that sent before V129 whose Meta still holds old wording, the first Reload rewords exactly those")
	void aPreV129TempleWithOldWordingIsReworded() throws Exception {
		aTempleThatSentBeforeV129();
		for (String name : REWORDED_BY_T159) {
			// Each under its own category: two of the six are MARKETING in the app (T-159), and a fixture
			// that held them as UTILITY would be testing a category mismatch, not a rewording.
			meta.hold(name, template(name).whatsappCategory(), "APPROVED", "An earlier wording of " + name + " for {{1}}.");
		}

		reload().andExpect(status().isOk());

		assertThat(meta.editedTemplateIds).containsExactlyInAnyOrderElementsOf(
				REWORDED_BY_T159.stream().map(name -> "id-" + name).toList());
		for (String name : REWORDED_BY_T159) {
			assertThat(meta.held(name).body()).isEqualTo(template(name).whatsappBodyText());
		}
		assertThat(storedFingerprints()).isEqualTo(everyFingerprintAsReleased());
		assertThat(storedList()).isEmpty();
	}

	@Test
	@DisplayName("saving a different account sets accountChanged and sends nothing; Reload compares every template on the new account and clears it")
	void aChangedAccountSetsAccountChangedUntilReload() throws Exception {
		save().andExpect(status().isOk());
		meta.resetCounts();

		save("phone-govinda", "waba-new").andExpect(status().isOk());

		assertThat(meta.creates.get()).as("a changed account is not sent to on Save").isZero();
		mvc.perform(get("/api/v1/settings/whatsapp"))
				.andExpect(jsonPath("$.templatesPending.accountChanged").value(true));

		reload().andExpect(status().isOk());

		assertThat(meta.createPaths).containsOnly("/waba-new/message_templates");
		assertThat(meta.lookups.get())
				.as("fingerprints recorded against the old account prove nothing about the new one")
				.isEqualTo(20 + STATUS_COPY_LOOKUPS);
		mvc.perform(get("/api/v1/settings/whatsapp"))
				.andExpect(jsonPath("$.templatesPending.accountChanged").value(false));

		// The phone number alone is also the account, by the screen's definition.
		save("phone-other", "waba-new").andExpect(status().isOk());
		mvc.perform(get("/api/v1/settings/whatsapp"))
				.andExpect(jsonPath("$.templatesPending.accountChanged").value(true));
	}

	// ---- Reload --------------------------------------------------------------------------------------

	/**
	 * Every kind of waiting at once, as a release would leave a temple: one template reworded, one new,
	 * one Meta refused last time. The lookup deliberately finds {@code volunteer_shift_reminder} too,
	 * because the fake's name filter matches part of a name, and only {@code shift_reminder} may be
	 * edited.
	 */
	@Test
	@DisplayName("Reload creates new templates, rewords changed ones through Meta's edit, retries refused ones, records fingerprints, replaces the list and stamps the date")
	void reloadSendsEverythingThatIsWaiting() throws Exception {
		meta.refuseCreating("temple_announcement", FakeMeta.TOO_MANY_VARIABLES);
		save().andExpect(status().isOk());
		assertThat(storedList()).containsOnlyKeys("temple_announcement");

		// A release since: shift_reminder was reworded (Meta and our record both hold the old wording),
		// and low_stock_digest is new (Meta has never had it, and our record has no key for it).
		meta.hold("shift_reminder", "UTILITY", "APPROVED", OLD_SHIFT_REMINDER);
		setStoredFingerprint("shift_reminder", "sha256:the-wording-before-this-release");
		meta.forget("low_stock_digest");
		removeStoredFingerprint("low_stock_digest");
		meta.allowCreating("temple_announcement");
		jdbc.update("""
				UPDATE tenant_settings SET whatsapp_templates_submitted_at = ?
				WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""", LONG_AGO);
		mvc.perform(get("/api/v1/settings/whatsapp"))
				.andExpect(jsonPath("$.templatesPending.changed").value(2))
				.andExpect(jsonPath("$.templatesPending.refused").value(1));
		meta.resetCounts();

		reload()
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.templatesPending.changed").value(0))
				.andExpect(jsonPath("$.templatesPending.refused").value(0))
				.andExpect(jsonPath("$.refusedTemplates.length()").value(0));

		assertThat(meta.held("low_stock_digest")).as("created").isNotNull();
		assertThat(meta.held("temple_announcement")).as("retried, and created").isNotNull();
		assertThat(meta.editedTemplateIds).as("only shift_reminder, not volunteer_shift_reminder").containsExactly("id-shift_reminder");
		assertThat(meta.held("shift_reminder").body()).isEqualTo(template("shift_reminder").whatsappBodyText());
		assertThat(meta.lastEditBody).contains("\"type\":\"BODY\"").doesNotContain("category");
		assertThat(meta.lookups.get()).as("only the template whose held wording was not known to be current").isEqualTo(1 + STATUS_COPY_LOOKUPS);

		assertThat(storedFingerprints()).isEqualTo(everyFingerprintAsReleased());
		assertThat(storedList()).isEmpty();
		assertThat(submittedAt()).isAfter(LONG_AGO);
		assertThat(admin.queryForObject("""
				SELECT before_state ->> 'whatsappTemplatesChanged' FROM audit_events
				WHERE tenant_id = ? AND reason = 'WhatsApp templates sent to Meta.'
				""", String.class, govinda)).isEqualTo("2");
	}

	/**
	 * The focused form of the rule the button depends on: after a Reload that brought Meta into line,
	 * the screen must say nothing is waiting. It starts from a temple WITH fingerprints on purpose. A
	 * temple without any reads zero whether or not a Reload records anything, and would prove nothing.
	 */
	@Test
	@DisplayName("nothing is waiting after a Reload that rewords a changed template")
	void nothingIsWaitingAfterReload() throws Exception {
		save().andExpect(status().isOk());
		meta.hold("po_delivery", "UTILITY", "APPROVED", "Purchase order {{1}} for {{2}} is ready: {{3}}. Raised {{4}}, needed by {{5}}.");
		setStoredFingerprint("po_delivery", "sha256:the-wording-before-this-release");
		assertThat(view().get("templatesPending").get("changed").asInt()).isEqualTo(1);

		reload().andExpect(status().isOk());

		assertThat(view().get("templatesPending").toString())
				.isEqualTo("{\"changed\":0,\"refused\":0,\"accountChanged\":false}");
	}

	@Test
	@DisplayName("Meta refusing new wording for its edit limit is stored with a true, plain reason, and not counted twice")
	void anEditLimitIsStoredTruthfully() throws Exception {
		save().andExpect(status().isOk());
		meta.hold("shift_reminder", "UTILITY", "APPROVED", OLD_SHIFT_REMINDER);
		setStoredFingerprint("shift_reminder", "sha256:the-wording-before-this-release");
		meta.answerEdits(FakeMeta.STATUS_CANNOT_BE_CHANGED);

		reload().andExpect(status().isOk());

		assertThat(meta.edits.get()).isEqualTo(1);
		JsonNode entry = storedList().get("shift_reminder");
		assertThat(entry.get("kind").asText()).isEqualTo("REFUSED");
		assertThat(entry.get("reason").asText())
				.isEqualTo(TenantWhatsAppSettingsService.EDIT_LIMIT)
				.doesNotContain("status").doesNotContain("template");
		assertThat(storedList()).hasSize(1);
		assertThat(storedFingerprints().get("shift_reminder")).as("Meta still holds the old wording").isEqualTo("null");
		assertThat(meta.held("shift_reminder").body()).isEqualTo(OLD_SHIFT_REMINDER);
		assertThat(view().get("templatesPending").toString())
				.isEqualTo("{\"changed\":0,\"refused\":1,\"accountChanged\":false}");
	}

	@Test
	@DisplayName("a template Meta is still reviewing is not edited at all, and the stored reason says it is in review")
	void aTemplateInReviewIsNotEdited() throws Exception {
		save().andExpect(status().isOk());
		meta.hold("shift_reminder", "UTILITY", "PENDING", OLD_SHIFT_REMINDER);
		setStoredFingerprint("shift_reminder", "sha256:the-wording-before-this-release");

		reload().andExpect(status().isOk());

		assertThat(meta.edits.get()).as("Meta allows no edit while in review, so none is attempted").isZero();
		JsonNode entry = storedList().get("shift_reminder");
		assertThat(entry.get("kind").asText()).isEqualTo("REFUSED");
		assertThat(entry.get("reason").asText()).isEqualTo(TenantWhatsAppSettingsService.STILL_IN_REVIEW);
	}

	@Test
	@DisplayName("2388039 on a template whose status Meta did not name says both causes Meta documents, not one guessed")
	void anUnnamedStatusGetsBothCauses() throws Exception {
		save().andExpect(status().isOk());
		meta.hold("shift_reminder", "UTILITY", null, OLD_SHIFT_REMINDER);
		setStoredFingerprint("shift_reminder", "sha256:the-wording-before-this-release");
		meta.answerEdits(FakeMeta.STATUS_CANNOT_BE_CHANGED);

		reload().andExpect(status().isOk());

		assertThat(storedList().get("shift_reminder").get("reason").asText())
				.isEqualTo(TenantWhatsAppSettingsService.IN_REVIEW_OR_EDIT_LIMIT);
	}

	@Test
	@DisplayName("Reload on a temple that has not connected is refused with the existing validation code, and Meta is never asked")
	void reloadBeforeConnectingIsRefused() throws Exception {
		reload()
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.reference()));

		assertThat(meta.creates.get() + meta.lookups.get() + meta.edits.get() + meta.phoneChecks.get()).isZero();
	}

	/**
	 * Through Spring Security's own {@code @PreAuthorize} interceptor rather than by reading the
	 * annotation alone: the controller is proxied with it, and the same proxy is shown letting a Temple
	 * Admin through, so a 403 here is the permission and not a proxy that refuses everyone.
	 */
	@Test
	@DisplayName("Reload is refused without MANAGE_TEMPLE_SETTINGS, and sends nothing")
	void reloadNeedsManageTempleSettings() throws Exception {
		PreAuthorize rule = WhatsAppSettingsController.class
				.getMethod("reloadTemplates", AuthenticatedUser.class).getAnnotation(PreAuthorize.class);
		assertThat(rule).isNotNull();
		assertThat(rule.value()).isEqualTo("hasAuthority('MANAGE_TEMPLE_SETTINGS')");

		ProxyFactory secured = new ProxyFactory(new WhatsAppSettingsController(service, emails));
		secured.setProxyTargetClass(true);
		secured.addAdvisor(AuthorizationManagerBeforeMethodInterceptor.preAuthorize());
		MockMvc securedMvc = mvcFor((WhatsAppSettingsController) secured.getProxy());
		save().andExpect(status().isOk());
		meta.resetCounts();

		signInAs("uid-volunteer-t169a");
		securedMvc.perform(post("/api/v1/settings/whatsapp/templates/reload"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value(ErrorCode.NOT_PERMITTED.reference()));
		assertThat(meta.creates.get() + meta.lookups.get() + meta.edits.get()).isZero();

		signInAs("uid-admin-t169a");
		securedMvc.perform(post("/api/v1/settings/whatsapp/templates/reload")).andExpect(status().isOk());
		assertThat(meta.creates.get()).isEqualTo(NotificationTemplate.values().length);
	}

	/**
	 * T-178: a temple's own Reload takes the stored copy of Meta's status that the operator's screens read,
	 * and records no operator audit entry, because the temple acted. Its own Reload entry is still written.
	 * The copy starts stale, with a row this release does not have, so a Reload that wrote nothing, or
	 * only added rows, cannot pass.
	 */
	@Test
	@DisplayName("a temple's own Reload replaces the stored copy of Meta's status and writes no operator audit entry")
	void reloadRefreshesTheStatusCopyWithoutAnOperatorAudit() throws Exception {
		save().andExpect(status().isOk());
		jdbc.update("""
				INSERT INTO whatsapp_template_status_copy (tenant_id, template_name, our_category, meta_status,
				    meta_category, held, wording_matches, taken_at)
				VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, 'a_retired_template', 'UTILITY',
				    'APPROVED', 'UTILITY', true, true, ?)
				""", LONG_AGO);
		meta.resetCounts();

		reload().andExpect(status().isOk());

		List<Map<String, Object>> copy = jdbc.queryForList(
				"SELECT template_name, meta_status, held, wording_matches FROM whatsapp_template_status_copy");
		assertThat(copy).extracting(row -> (String) row.get("template_name"))
				.containsExactlyInAnyOrderElementsOf(Arrays.stream(NotificationTemplate.values())
						.map(NotificationTemplate::whatsappTemplateName).toList());
		Map<String, Object> shiftReminder = copy.stream()
				.filter(row -> "shift_reminder".equals(row.get("template_name"))).findFirst().orElseThrow();
		assertThat(shiftReminder.get("meta_status")).as("the first Save left Meta reviewing it").isEqualTo("PENDING");
		assertThat(shiftReminder.get("held")).isEqualTo(true);
		assertThat(shiftReminder.get("wording_matches")).isEqualTo(true);
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM whatsapp_template_status_copy WHERE taken_at <= ?", Integer.class, LONG_AGO))
				.as("nothing left from the stale copy").isZero();
		assertThat(meta.lookups.get()).as("Reload needed no lookups of its own; the copy took one per template")
				.isEqualTo(STATUS_COPY_LOOKUPS);

		assertThat(admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = 'WHATSAPP_TEMPLATE_STATUS_REFRESHED'", Integer.class))
				.as("no operator audit entry anywhere").isZero();
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE tenant_id = ? AND reason = 'WhatsApp templates sent to Meta.'",
				Integer.class, govinda)).as("the temple's own Reload entry").isEqualTo(1);
	}

	// ---- Meta, played by a local server that keeps what it holds -------------------------------------

	/** One answer from Meta. */
	private record MetaAnswer(int status, String body) {
	}

	/** A fake Graph API holding templates for any business account (the fake does not separate them). */
	static final class FakeMeta {

		/** T-159's staging refusal. */
		static final MetaAnswer TOO_MANY_VARIABLES = new MetaAnswer(400, """
				{"error":{"message":"Invalid parameter","type":"OAuthException","code":100,"error_subcode":2388293,
				"error_user_title":"Parameters words ratio exceeds limit",
				"error_user_msg":"This template has too many variables for its length. Reduce the number of variables or increase the message length."}}
				""");

		/** Meta's documented 2388039, with the title and sentence from its error codes page. */
		static final MetaAnswer STATUS_CANNOT_BE_CHANGED = new MetaAnswer(400, """
				{"error":{"message":"Invalid parameter","type":"OAuthException","code":100,"error_subcode":2388039,
				"is_transient":false,"error_user_title":"Message template status can't be changed",
				"error_user_msg":"The status for this message template can't be changed. You can only delete or add templates.",
				"fbtrace_id":"AQc1T169aaaa"}}
				""");

		private static final String ALREADY_EXISTS = """
				{"error":{"message":"Invalid parameter","type":"OAuthException","code":100,"error_subcode":2388024,
				"error_user_title":"Content in This Language Already Exists",
				"error_user_msg":"There is already English content for this template. You can create a new template and try again."}}
				""";

		record Held(String id, String name, String category, String status, String body) {
		}

		private final ObjectMapper json;
		private final HttpServer server;
		private final Map<String, Held> holds = new ConcurrentHashMap<>();
		private final Map<String, MetaAnswer> createRefusals = new ConcurrentHashMap<>();
		private volatile MetaAnswer editAnswer;

		final AtomicInteger phoneChecks = new AtomicInteger();
		final AtomicInteger creates = new AtomicInteger();
		final AtomicInteger lookups = new AtomicInteger();
		final AtomicInteger edits = new AtomicInteger();
		final List<String> createPaths = Collections.synchronizedList(new ArrayList<>());
		final List<String> editedTemplateIds = Collections.synchronizedList(new ArrayList<>());
		volatile String lastEditBody;

		FakeMeta(ObjectMapper json) throws IOException {
			this.json = json;
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/", this::handle);
			server.start();
		}

		String url() {
			return "http://127.0.0.1:" + server.getAddress().getPort();
		}

		void stop() {
			server.stop(0);
		}

		void resetCounts() {
			phoneChecks.set(0);
			creates.set(0);
			lookups.set(0);
			edits.set(0);
			createPaths.clear();
			editedTemplateIds.clear();
		}

		void hold(String name, String category, String status, String body) {
			holds.put(name, new Held("id-" + name, name, category, status, body));
		}

		void holdEveryTemplateAsReleased() {
			for (NotificationTemplate template : NotificationTemplate.values()) {
				hold(template.whatsappTemplateName(), template.whatsappCategory(), "APPROVED", template.whatsappBodyText());
			}
		}

		void forget(String name) {
			holds.remove(name);
		}

		Held held(String name) {
			return holds.get(name);
		}

		void refuseCreating(String name, MetaAnswer answer) {
			createRefusals.put(name, answer);
		}

		void allowCreating(String name) {
			createRefusals.remove(name);
		}

		void answerEdits(MetaAnswer answer) {
			editAnswer = answer;
		}

		private void handle(HttpExchange exchange) throws IOException {
			String method = exchange.getRequestMethod();
			String path = exchange.getRequestURI().getPath();
			String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			MetaAnswer answer;
			if ("GET".equals(method) && path.endsWith("/message_templates")) {
				answer = lookup(exchange.getRequestURI().getRawQuery());
			} else if ("GET".equals(method)) {
				phoneChecks.incrementAndGet();
				answer = new MetaAnswer(200, "{\"display_phone_number\":\"+1 555-010-0169\",\"verified_name\":\"Temple Kitchen\"}");
			} else if (path.endsWith("/message_templates")) {
				answer = create(path, json.readTree(request));
			} else {
				answer = edit(path.substring(1), request);
			}
			byte[] out = answer.body().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(answer.status(), out.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(out);
			}
		}

		private MetaAnswer create(String path, JsonNode request) throws IOException {
			creates.incrementAndGet();
			createPaths.add(path);
			String name = request.path("name").asText();
			String category = request.path("category").asText();
			if (createRefusals.containsKey(name)) {
				return createRefusals.get(name);
			}
			Held held = holds.get(name);
			if (held != null && !held.category().equals(category)) {
				return new MetaAnswer(400, json.writeValueAsString(Map.of("error", Map.of(
						"message", "Invalid parameter", "type", "OAuthException", "code", 100,
						"error_user_msg", "The category " + category + " doesn't match the one that's already "
								+ "associated with this template, " + held.category() + "."))));
			}
			if (held != null) {
				return new MetaAnswer(400, ALREADY_EXISTS);
			}
			hold(name, category, "PENDING", request.path("components").get(0).path("text").asText());
			return new MetaAnswer(200, "{\"id\":\"id-" + name + "\",\"status\":\"PENDING\",\"category\":\"" + category + "\"}");
		}

		private MetaAnswer lookup(String rawQuery) throws IOException {
			lookups.incrementAndGet();
			String asked = Arrays.stream(rawQuery.split("&"))
					.filter(p -> p.startsWith("name="))
					.map(p -> URLDecoder.decode(p.substring(5), StandardCharsets.UTF_8))
					.findFirst().orElse("");
			// Part of a name matches, on purpose: see the class comment.
			List<Map<String, Object>> data = holds.values().stream()
					.filter(h -> h.name().contains(asked))
					.sorted((a, b) -> b.name().length() - a.name().length())
					.map(h -> {
						Map<String, Object> entry = new LinkedHashMap<>();
						entry.put("id", h.id());
						entry.put("name", h.name());
						entry.put("language", "en");
						if (h.status() != null) {
							entry.put("status", h.status());
						}
						entry.put("category", h.category());
						entry.put("components", List.of(Map.of("type", "BODY", "text", h.body())));
						return entry;
					})
					.collect(Collectors.toList());
			return new MetaAnswer(200, json.writeValueAsString(Map.of("data", data)));
		}

		private MetaAnswer edit(String templateId, String request) throws IOException {
			edits.incrementAndGet();
			editedTemplateIds.add(templateId);
			lastEditBody = request;
			if (editAnswer != null) {
				return editAnswer;
			}
			Held held = holds.values().stream().filter(h -> h.id().equals(templateId)).findFirst().orElse(null);
			if (held == null) {
				return new MetaAnswer(400, "{\"error\":{\"message\":\"Unsupported post request.\",\"code\":100}}");
			}
			String body = json.readTree(request).path("components").get(0).path("text").asText();
			holds.put(held.name(), new Held(held.id(), held.name(), held.category(), "PENDING", body));
			return new MetaAnswer(200, "{\"success\":true}");
		}
	}
}
