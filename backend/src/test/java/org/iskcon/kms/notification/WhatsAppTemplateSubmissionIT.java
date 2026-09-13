package org.iskcon.kms.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * <p>The database is real and the application connects as the unprivileged {@code kms_app} role, so
 * every read and write here passes through {@code tenant_settings}' Row-Level Security policy.
 */
class WhatsAppTemplateSubmissionIT extends AbstractIntegrationTest {

	/** The six Meta refused on staging. Every other template is accepted. */
	private static final Set<String> REFUSED_ON_STAGING = Set.of(
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
	private MetaWhatsAppClient meta;
	private MockMvc mvc;
	private UUID govinda;
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

		TenantWhatsAppSettingsService target = new TenantWhatsAppSettingsService(
				jdbc, secrets, auditService, meta, "https://kms.example");
		ProxyFactory proxy = new ProxyFactory(target);
		proxy.setProxyTargetClass(true);
		proxy.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
		TenantWhatsAppSettingsService service = (TenantWhatsAppSettingsService) proxy.getProxy();

		mvc = MockMvcBuilders.standaloneSetup(new WhatsAppSettingsController(service, emails))
				.setControllerAdvice(new GlobalExceptionHandler())
				.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
				.setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
				.build();

		TenantContext.set(govinda);
		AuthenticatedUser actor = new AuthenticatedUser(users.findByFirebaseUid("uid-admin-t159").orElseThrow());
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(actor, null, actor.getAuthorities()));
	}

	@AfterEach
	void tearDown() {
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
			"shift_reminder", TOO_MANY_VARIABLES,
			"po_delivery", TOO_MANY_VARIABLES,
			"shift_broadcast", TOO_MANY_VARIABLES,
			"temple_announcement", TOO_MANY_VARIABLES,
			"temple_communication", "The message body can't have more than two consecutive newline characters, "
					+ "only have parameters, or have more than 10 emojis.",
			"low_stock_digest", "Variables can't be at the start or end of the template.");

	private static MetaWhatsAppClient.TemplateSubmission accepted() {
		return new MetaWhatsAppClient.TemplateSubmission(MetaWhatsAppClient.TemplateOutcome.SUBMITTED, null);
	}

	/** Meta accepting everything except the six it refused on staging, refusing those in its own words. */
	private void metaRefusesWhatItRefusedOnStaging() {
		when(meta.createTemplate(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
				anyList()))
				.thenAnswer(call -> {
					String name = call.getArgument(2);
					return REFUSED_ON_STAGING.contains(name)
							? new MetaWhatsAppClient.TemplateSubmission(
									MetaWhatsAppClient.TemplateOutcome.REFUSED, META_SAID.get(name))
							: accepted();
				});
	}

	private void metaRefusesEverything() {
		when(meta.createTemplate(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
				anyList()))
				.thenReturn(new MetaWhatsAppClient.TemplateSubmission(
						MetaWhatsAppClient.TemplateOutcome.REFUSED, "Something Meta has never said before."));
	}

	private void metaAcceptsEverything() {
		when(meta.createTemplate(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
				anyList()))
				.thenReturn(accepted());
	}

	/** The refused list straight from tenant_settings, as the app role sees it, as name → reason. */
	private Map<String, String> storedRefusals() throws Exception {
		String stored = jdbc.queryForObject("SELECT whatsapp_refused_templates::text FROM tenant_settings", String.class);
		Map<String, String> byName = new java.util.TreeMap<>();
		objectMapper.readTree(stored).forEach(e -> byName.put(e.get("name").asText(), e.get("reason").asText()));
		return byName;
	}

	private ResultActions saveSettings() throws Exception {
		return mvc.perform(put("/api/v1/settings/whatsapp")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
						"phoneNumberId", "phone-govinda", "wabaId", "waba-govinda",
						"accessToken", "token-govinda", "appSecret", "secret-govinda"))));
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
		assertThat(stored.get("shift_reminder")).startsWith("Meta found too little fixed wording");
		assertThat(stored.get("low_stock_digest")).startsWith("Meta will not accept a message that begins or ends");
		assertThat(stored.get("temple_communication")).startsWith("Meta found no fixed wording of its own");
		stored.values().forEach(reason -> assertThat(reason)
				.as("a sentence for an administrator, not Meta's developer text")
				.doesNotContainIgnoringCase("variables").doesNotContainIgnoringCase("template")
				.doesNotContainIgnoringCase("newline").doesNotContainIgnoringCase("parameters"));

		// And it reaches the answer the screen reads.
		mvc.perform(get("/api/v1/settings/whatsapp"))
				.andExpect(jsonPath("$.refusedTemplates.length()").value(6))
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
						.startsWith("Meta did not accept this message. Press Save to try again")
						.doesNotContain("never said before"));
	}

	@Test
	@DisplayName("a template Meta could not be asked about is kept too, with its own reason")
	void anUnreachableMetaIsRecorded() throws Exception {
		when(meta.createTemplate(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
				anyList()))
				.thenAnswer(call -> {
					if ("shift_cancelled".equals(call.getArgument(2))) {
						throw new MetaWhatsAppClient.WhatsAppCredentialsRejected("Could not reach Meta just now.");
					}
					return accepted();
				});

		saveSettings().andExpect(status().isOk());

		assertThat(storedRefusals()).containsOnlyKeys("shift_cancelled");
		assertThat(storedRefusals().get("shift_cancelled")).startsWith("Meta could not be reached");
	}

	/**
	 * The list is a snapshot of the last save, not a history. Note for a negative control: with the
	 * write removed this test passes vacuously, because it ends by asserting an absence and a list
	 * that was never written is also empty. {@link #refusalsAreStoredInPlainWords} is the one that
	 * proves the write.
	 */
	@Test
	@DisplayName("a later save where Meta accepts everything clears the list")
	void aCleanSaveClearsTheList() throws Exception {
		metaRefusesWhatItRefusedOnStaging();
		saveSettings().andExpect(status().isOk());

		org.mockito.Mockito.reset(meta);
		when(meta.verifyNumber(anyString(), anyString())).thenReturn("Temple Kitchen (+1 555-010-0159)");
		metaAcceptsEverything();
		saveSettings().andExpect(status().isOk());

		assertThat(storedRefusals()).isEmpty();
		mvc.perform(get("/api/v1/settings/whatsapp"))
				.andExpect(jsonPath("$.refusedTemplates").isArray())
				.andExpect(jsonPath("$.refusedTemplates.length()").value(0));
	}
}
