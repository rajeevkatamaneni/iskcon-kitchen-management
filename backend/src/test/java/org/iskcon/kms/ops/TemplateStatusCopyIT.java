package org.iskcon.kms.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.error.GlobalExceptionHandler;
import org.iskcon.kms.notification.MetaWhatsAppClient;
import org.iskcon.kms.notification.NotificationTemplate;
import org.iskcon.kms.notification.WhatsAppTemplateComparison;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.tenancy.TenantSecretStore;
import org.iskcon.kms.user.UserRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * One temple's stored copy of Meta's WhatsApp template status, read and refreshed by the platform
 * operator (T-178).
 *
 * <p><strong>What is asserted at the far side of each boundary.</strong>
 * <ul>
 *   <li><strong>Meta:</strong> a local {@link HttpServer} the production {@link MetaWhatsAppClient} is
 *       pointed at. It counts every request by method and records the path and Authorization header of
 *       each. Every POST is refused and counted, per test in {@link #tearDown} and for the class in
 *       {@link #noTestInThisClassPostedToMeta}. The counter is shown to be live: the refresh test asserts
 *       exactly nineteen lookups.</li>
 *   <li><strong>The token:</strong> every log event of every test, with the four classes involved raised
 *       to DEBUG, and every response body, is searched for both temples' tokens in {@link #tearDown}. The
 *       stub proves the token really was sent, and that it was the refreshed temple's own.</li>
 *   <li><strong>The audit log:</strong> read as the container superuser, so a row written to the wrong
 *       temple is counted rather than hidden by RLS.</li>
 *   <li><strong>Isolation:</strong> read as {@code kms_app}, through the application's own tenant-aware
 *       DataSource, which is what RLS constrains.</li>
 *   <li><strong>Permissions:</strong> the controller is proxied with Spring Security's real
 *       {@code @PreAuthorize} interceptor, and the same proxy is shown letting a Super Admin through, so a
 *       403 is the permission and not a proxy that refuses everyone.</li>
 * </ul>
 *
 * <p>Built by hand and wrapped in the application's own transaction interceptor, as
 * {@code WhatsAppTemplateComparisonIT} is, so the class carries no {@code @Import}, {@code @MockBean} or
 * {@code @TestPropertySource} and shares the suite's cached context. No Spring bean that talks to Meta is
 * used, so nothing here can reach the real Graph API.
 */
class TemplateStatusCopyIT extends AbstractIntegrationTest {

	/** Shaped like Meta's tokens, and unique enough that finding one anywhere means it leaked. */
	private static final String GOVINDA_TOKEN = "EAAGt178GovindaNeverShownOrLoggedZq8x";
	private static final String KRISHNA_TOKEN = "EAAGt178KrishnaNeverShownOrLoggedQ3w";

	private static final OffsetDateTime LONG_AGO = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

	private static final Set<String> VIEW_FIELDS = Set.of("tenantId", "asOf", "templates");
	private static final Set<String> ROW_FIELDS = Set.of("name", "ourCategory", "metaStatus", "metaCategory",
			"held", "wordingMatches", "lookupProblem");

	private static final AtomicInteger POSTS_ACROSS_THE_CLASS = new AtomicInteger();

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TenantSecretStore secrets;

	@Autowired
	private AuditService auditService;

	@Autowired
	private UserRepository users;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private JdbcTemplate admin;
	private UUID govinda;
	private UUID krishna;
	private FakeMeta meta;
	private MockMvc mvc;
	private ListAppender<ILoggingEvent> logs;
	private final Map<String, Level> levelsBefore = new LinkedHashMap<>();
	private final List<String> responseBodies = new ArrayList<>();

	@BeforeEach
	void setUp() throws Exception {
		TenantContext.clear();
		admin = new JdbcTemplate(adminDataSource());
		govinda = insertTenant("radha-govinda-t178", "Sri Sri Radha Govinda Temple");
		krishna = insertTenant("krishna-balaram-t178", "Sri Krishna Balaram Temple");
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (NULL, 'uid-super-t178', 'Platform Operator', 'super-t178@example.com', '+919000000178',
						'SUPER_ADMIN', 'ACTIVE'),
					   (?, 'uid-admin-t178', 'Temple Admin', 'admin-t178@example.com', '+919876500178',
						'TEMPLE_ADMIN', 'ACTIVE')
				""", govinda);

		logs = new ListAppender<>();
		logs.start();
		root().addAppender(logs);
		for (Class<?> type : List.of(MetaWhatsAppClient.class, WhatsAppTemplateComparison.class, OpsService.class,
				TemplateStatusCopy.class)) {
			ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(type);
			levelsBefore.put(type.getName(), logger.getLevel());
			logger.setLevel(Level.DEBUG);
		}

		meta = new FakeMeta(objectMapper);
		MetaWhatsAppClient client = new MetaWhatsAppClient(objectMapper, meta.url());
		WhatsAppTemplateComparison comparison = transactional(new WhatsAppTemplateComparison(jdbc, secrets, client));
		TemplateStatusCopy copy = transactional(new TemplateStatusCopy(jdbc, auditService));
		OpsService ops = new OpsService(jdbc, comparison, copy);

		ProxyFactory secured = new ProxyFactory(new OpsController(ops, new WhatsAppTemplateCatalogue(jdbc)));
		secured.setProxyTargetClass(true);
		secured.addAdvisor(AuthorizationManagerBeforeMethodInterceptor.preAuthorize());
		mvc = MockMvcBuilders.standaloneSetup(secured.getProxy())
				.setControllerAdvice(new GlobalExceptionHandler())
				.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
				.setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
				.build();
	}

	/** State is put back before anything is asserted, so a failure here leaves nothing for the next test. */
	@AfterEach
	void tearDown() {
		meta.stop();
		int posts = meta.posts.get();
		int others = meta.otherRequests.get();
		POSTS_ACROSS_THE_CLASS.addAndGet(posts);
		root().detachAppender(logs);
		levelsBefore.forEach((name, level) ->
				((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(name)).setLevel(level));
		SecurityContextHolder.clearContext();
		TenantContext.clear();
		secrets.deleteAll(govinda);
		secrets.deleteAll(krishna);
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM whatsapp_template_status_copy");
		admin.execute("DELETE FROM tenant_settings");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");

		assertThat(posts).as("POSTs this test sent to Meta").isZero();
		assertThat(others).as("requests to Meta that were neither GET nor POST").isZero();
		for (ILoggingEvent event : logs.list) {
			String line = event.getFormattedMessage()
					+ (event.getThrowableProxy() == null ? "" : ThrowableProxyUtil.asString(event.getThrowableProxy()));
			assertThat(line).as("a log line from " + event.getLoggerName())
					.doesNotContain(GOVINDA_TOKEN).doesNotContain(KRISHNA_TOKEN);
		}
		for (String body : responseBodies) {
			assertThat(body).as("a response body").doesNotContain(GOVINDA_TOKEN).doesNotContain(KRISHNA_TOKEN);
		}
	}

	@AfterAll
	static void noTestInThisClassPostedToMeta() {
		assertThat(POSTS_ACROSS_THE_CLASS.get()).as("POSTs to Meta across TemplateStatusCopyIT").isZero();
	}

	// ---- the tests ---------------------------------------------------------------------------------

	@Test
	@DisplayName("Refresh as a Super Admin asks Meta with that temple's own token, replaces its copy, and writes exactly one audit entry on that temple")
	void refreshReplacesTheCopyAndIsAuditedOnce() throws Exception {
		connect(govinda, "waba-govinda", GOVINDA_TOKEN);
		connect(krishna, "waba-krishna", KRISHNA_TOKEN);
		seedCopyRow(govinda, "a_retired_template", "APPROVED");
		seedCopyRow(govinda, "volunteer_shift_reminder", "APPROVED");
		meta.holdEveryTemplateAsReleased("PENDING");
		meta.hold("donation_thank_you", "MARKETING", "PENDING", body("donation_thank_you"), null);
		meta.hold("po_delivery", "UTILITY", "REJECTED", body("po_delivery"), "INVALID_FORMAT");
		meta.hold("shift_broadcast", "UTILITY", "APPROVED", "An older wording for {{1}}.", null);
		meta.forget("leave_revoked");
		meta.failFor("low_stock_digest");
		signInAs("uid-super-t178");

		JsonNode view = json(mvc.perform(post("/api/v1/ops/tenants/{id}/whatsapp-templates/refresh", govinda)), 200);

		// The answer, in exactly api.ts's shape.
		assertThat(fieldNames(view)).isEqualTo(VIEW_FIELDS);
		assertThat(view.get("tenantId").asText()).isEqualTo(govinda.toString());
		assertThat(Instant.parse(view.get("asOf").asText())).isAfter(LONG_AGO.toInstant());
		Map<String, JsonNode> rows = byName(view);
		assertThat(rows.keySet()).as("every template this release sends, and the retired row gone")
				.containsExactlyInAnyOrderElementsOf(allNames());
		rows.values().forEach(row -> assertThat(fieldNames(row)).isEqualTo(ROW_FIELDS));
		assertThat(view.get("templates").get(0).get("name").asText())
				.as("in the release's own order").isEqualTo(NotificationTemplate.values()[0].whatsappTemplateName());

		assertRow(rows.get("volunteer_shift_reminder"), "PENDING", "UTILITY", true, true);
		assertThat(rows.get("volunteer_shift_reminder").get("lookupProblem").isNull()).isTrue();
		assertRow(rows.get("donation_thank_you"), "PENDING", "MARKETING", true, true);
		assertThat(rows.get("donation_thank_you").get("ourCategory").asText())
				.isEqualTo(template("donation_thank_you").whatsappCategory());
		assertRow(rows.get("po_delivery"), "REJECTED", "UTILITY", true, true);
		assertRow(rows.get("shift_broadcast"), "APPROVED", "UTILITY", true, false);
		assertThat(rows.get("leave_revoked").get("held").asBoolean()).isFalse();
		assertThat(rows.get("leave_revoked").get("metaStatus").isNull()).isTrue();
		assertThat(rows.get("leave_revoked").get("wordingMatches").isNull()).isTrue();
		assertThat(rows.get("low_stock_digest").get("held").isNull()).as("Meta did not answer").isTrue();
		assertThat(rows.get("low_stock_digest").get("lookupProblem").asText()).isNotBlank();

		// What is stored, read past RLS so another temple's rows would be seen too.
		assertThat(count("SELECT count(*) FROM whatsapp_template_status_copy WHERE tenant_id = ?", govinda))
				.isEqualTo(NotificationTemplate.values().length);
		assertThat(count("SELECT count(*) FROM whatsapp_template_status_copy WHERE tenant_id = ?", krishna)).isZero();
		assertThat(count("SELECT count(DISTINCT taken_at) FROM whatsapp_template_status_copy WHERE tenant_id = ?", govinda))
				.as("one copy, one time").isEqualTo(1);
		assertThat(admin.queryForObject("""
				SELECT meta_rejected_reason FROM whatsapp_template_status_copy
				WHERE tenant_id = ? AND template_name = 'po_delivery'
				""", String.class, govinda)).isEqualTo("INVALID_FORMAT");

		// Exactly one audit entry, on this temple, saying what was stored before and after.
		assertThat(count("SELECT count(*) FROM audit_events WHERE tenant_id = ?", krishna)).isZero();
		assertThat(admin.queryForList("SELECT action FROM audit_events WHERE tenant_id = ?", String.class, govinda))
				.containsExactly("WHATSAPP_TEMPLATE_STATUS_REFRESHED");
		Map<String, Object> audit = admin.queryForMap("""
				SELECT entity_type, entity_id, actor_label, reason, before_state ->> 'asOf' AS before_as_of,
				       before_state ->> 'templates' AS before_templates, after_state ->> 'asOf' AS after_as_of,
				       after_state ->> 'templates' AS after_templates, after_state ->> 'refused' AS after_refused,
				       after_state::text AS after_text
				FROM audit_events WHERE tenant_id = ?
				""", govinda);
		assertThat(audit.get("entity_type")).isEqualTo("TENANT");
		assertThat(audit.get("entity_id")).isEqualTo(govinda);
		assertThat((String) audit.get("actor_label")).contains("SUPER_ADMIN");
		assertThat(audit.get("reason")).isEqualTo(TemplateStatusCopy.REFRESHED_BY_OPERATOR);
		assertThat(audit.get("before_as_of")).isEqualTo(LONG_AGO.toInstant().toString());
		assertThat(audit.get("before_templates")).isEqualTo("2");
		assertThat(audit.get("after_as_of")).isEqualTo(view.get("asOf").asText());
		assertThat(audit.get("after_templates")).isEqualTo(String.valueOf(NotificationTemplate.values().length));
		assertThat(audit.get("after_refused")).isEqualTo("1");
		assertThat((String) audit.get("after_text")).doesNotContain(GOVINDA_TOKEN);

		// The temple's own account and token, and GETs only.
		assertThat(meta.lookups.get()).isEqualTo(NotificationTemplate.values().length);
		assertThat(meta.lookupPaths).containsOnly("/waba-govinda/message_templates");
		assertThat(meta.authorizations).containsOnly("Bearer " + GOVINDA_TOKEN);

		// And reading it back gives exactly what the refresh answered.
		JsonNode reread = json(mvc.perform(get("/api/v1/ops/tenants/{id}/whatsapp-templates", govinda)), 200);
		assertThat(reread).isEqualTo(view);
		assertThat(meta.lookups.get()).as("reading never asks Meta").isEqualTo(NotificationTemplate.values().length);
	}

	/**
	 * The annotation is checked, and then the proxy: a Temple Admin, who does not hold MANAGE_TENANTS, is
	 * refused before Meta or the audit log is touched, and the same proxy then lets the Super Admin through.
	 */
	@Test
	@DisplayName("Refresh without MANAGE_TENANTS is refused with 403, with no call to Meta, no audit and the copy untouched")
	void refreshNeedsManageTenants() throws Exception {
		assertThat(rule("refreshTempleTemplateStatus", UUID.class, AuthenticatedUser.class))
				.isEqualTo("hasAuthority('MANAGE_TENANTS')");
		connect(govinda, "waba-govinda", GOVINDA_TOKEN);
		seedCopyRow(govinda, "volunteer_shift_reminder", "APPROVED");
		meta.holdEveryTemplateAsReleased("APPROVED");

		signInAs("uid-admin-t178");
		assertThat(currentAuthorities()).doesNotContain("MANAGE_TENANTS");
		MockHttpServletResponse refused = mvc.perform(
				post("/api/v1/ops/tenants/{id}/whatsapp-templates/refresh", govinda)).andReturn().getResponse();
		responseBodies.add(refused.getContentAsString());

		assertThat(refused.getStatus()).isEqualTo(403);
		assertThat(objectMapper.readTree(refused.getContentAsString()).get("code").asText())
				.isEqualTo(ErrorCode.NOT_PERMITTED.reference());
		assertThat(meta.requests.get()).as("requests to Meta").isZero();
		assertThat(count("SELECT count(*) FROM audit_events WHERE tenant_id = ?", govinda)).isZero();
		assertThat(count("SELECT count(*) FROM whatsapp_template_status_copy WHERE tenant_id = ?", govinda)).isEqualTo(1);

		signInAs("uid-super-t178");
		json(mvc.perform(post("/api/v1/ops/tenants/{id}/whatsapp-templates/refresh", govinda)), 200);
		assertThat(count("SELECT count(*) FROM audit_events WHERE tenant_id = ?", govinda)).isEqualTo(1);
	}

	@Test
	@DisplayName("reading a temple's copy and the counts needs VIEW_PLATFORM_OPERATIONS, and a temple never refreshed reads as not taken")
	void readingNeedsViewPlatformOperations() throws Exception {
		assertThat(rule("templeTemplateStatus", UUID.class)).isEqualTo("hasAuthority('VIEW_PLATFORM_OPERATIONS')");
		assertThat(rule("whatsappTemplateStatusCounts")).isEqualTo("hasAuthority('VIEW_PLATFORM_OPERATIONS')");

		signInAs("uid-admin-t178");
		assertThat(currentAuthorities()).doesNotContain("VIEW_PLATFORM_OPERATIONS");
		for (MockHttpServletRequestBuilder request : List.of(
				get("/api/v1/ops/tenants/{id}/whatsapp-templates", govinda),
				get("/api/v1/ops/whatsapp-templates/status-counts"))) {
			MockHttpServletResponse refused = mvc.perform(request).andReturn().getResponse();
			assertThat(refused.getStatus()).isEqualTo(403);
			assertThat(objectMapper.readTree(refused.getContentAsString()).get("code").asText())
					.isEqualTo(ErrorCode.NOT_PERMITTED.reference());
		}

		signInAs("uid-super-t178");
		JsonNode view = json(mvc.perform(get("/api/v1/ops/tenants/{id}/whatsapp-templates", govinda)), 200);
		assertThat(fieldNames(view)).isEqualTo(VIEW_FIELDS);
		assertThat(view.get("asOf").isNull()).isTrue();
		assertThat(view.get("templates").size()).isZero();
		assertThat(meta.requests.get()).isZero();
	}

	@Test
	@DisplayName("Refresh on a temple with no WhatsApp connection is refused with the existing validation code, before Meta or the audit log")
	void refreshOnATempleNotConnectedIsRefused() throws Exception {
		signInAs("uid-super-t178");

		MockHttpServletResponse response = mvc.perform(
				post("/api/v1/ops/tenants/{id}/whatsapp-templates/refresh", krishna)).andReturn().getResponse();
		responseBodies.add(response.getContentAsString());

		assertThat(objectMapper.readTree(response.getContentAsString()).get("code").asText())
				.isEqualTo(ErrorCode.VALIDATION_FAILED.reference());
		assertThat(meta.requests.get()).isZero();
		assertThat(count("SELECT count(*) FROM audit_events WHERE tenant_id = ?", krishna)).isZero();
	}

	@Test
	@DisplayName("a temple that does not exist is not found, for the read and the refresh, and Meta is never asked")
	void anUnknownTempleIsNotFound() throws Exception {
		signInAs("uid-super-t178");
		UUID nowhere = UUID.randomUUID();

		for (MockHttpServletRequestBuilder request : List.of(
				get("/api/v1/ops/tenants/{id}/whatsapp-templates", nowhere),
				post("/api/v1/ops/tenants/{id}/whatsapp-templates/refresh", nowhere))) {
			MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
			assertThat(objectMapper.readTree(response.getContentAsString()).get("code").asText())
					.isEqualTo(ErrorCode.TENANT_NOT_FOUND.reference());
		}
		assertThat(meta.requests.get()).isZero();
	}

	/**
	 * Proven as the application role, on the application's own tenant-aware DataSource, because a
	 * superuser would see every row whatever the policy said.
	 */
	@Test
	@DisplayName("RLS: one temple's copy is invisible from another temple's context and unwritable from it, as kms_app")
	void oneTemplesCopyIsInvisibleFromAnother() throws Exception {
		seedCopyRow(govinda, "volunteer_shift_reminder", "APPROVED");
		seedCopyRow(govinda, "po_delivery", "REJECTED");
		seedCopyRow(krishna, "volunteer_shift_reminder", "PENDING");

		assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
		assertThat(admin.queryForObject("""
				SELECT relrowsecurity AND relforcerowsecurity FROM pg_class
				WHERE relname = 'whatsapp_template_status_copy' AND relnamespace = 'public'::regnamespace
				""", Boolean.class)).isTrue();

		TenantContext.set(krishna);
		try {
			assertThat(jdbc.queryForObject(
					"SELECT count(*) FROM whatsapp_template_status_copy WHERE tenant_id = ?", Integer.class, govinda))
					.as("govinda's rows from krishna's context").isZero();
			assertThat(jdbc.queryForObject("SELECT count(*) FROM whatsapp_template_status_copy", Integer.class))
					.as("an unfiltered read sees krishna's own row only").isEqualTo(1);
			assertThat(jdbc.update("UPDATE whatsapp_template_status_copy SET meta_status = 'APPROVED' WHERE tenant_id = ?",
					govinda)).as("govinda's rows updated from krishna's context").isZero();
			assertThatThrownBy(() -> jdbc.update("""
					INSERT INTO whatsapp_template_status_copy (tenant_id, template_name, our_category, held, wording_matches)
					VALUES (?, 'donation_receipt', 'UTILITY', false, NULL)
					""", govinda)).rootCause().hasMessageContaining("row-level security");
		} finally {
			TenantContext.clear();
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM whatsapp_template_status_copy", Integer.class))
				.as("no context, no rows").isZero();
		assertThat(count("SELECT count(*) FROM whatsapp_template_status_copy WHERE tenant_id = ?", govinda))
				.as("govinda's rows are still there, as the superuser sees").isEqualTo(2);

		// And the operator's read goes through the same policy: govinda's page shows govinda's rows only.
		signInAs("uid-super-t178");
		JsonNode view = json(mvc.perform(get("/api/v1/ops/tenants/{id}/whatsapp-templates", govinda)), 200);
		assertThat(byName(view).keySet()).containsExactly("po_delivery", "volunteer_shift_reminder");
		assertThat(byName(view).get("volunteer_shift_reminder").get("metaStatus").asText()).isEqualTo("APPROVED");
	}

	// ---- helpers ------------------------------------------------------------------------------------

	@SuppressWarnings("unchecked")
	private <T> T transactional(T target) {
		ProxyFactory proxy = new ProxyFactory(target);
		proxy.setProxyTargetClass(true);
		proxy.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
		return (T) proxy.getProxy();
	}

	private UUID insertTenant(String slug, String name) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class, slug, name);
	}

	/** A temple connected to its own WhatsApp account, with its own token in the secret store. */
	private void connect(UUID temple, String wabaId, String token) {
		admin.update("""
				INSERT INTO tenant_settings (tenant_id, whatsapp_phone_number_id, whatsapp_waba_id, whatsapp_webhook_token)
				VALUES (?, ?, ?, ?)
				""", temple, "phone-" + wabaId, wabaId, "webhook-" + wabaId);
		secrets.put(temple, TenantSecretStore.Kind.WHATSAPP_ACCESS_TOKEN, token);
	}

	/** A stored row taken long ago, seeded as the superuser so a test can start from a known copy. */
	private void seedCopyRow(UUID temple, String name, String status) {
		admin.update("""
				INSERT INTO whatsapp_template_status_copy (tenant_id, template_name, our_category, meta_status,
				    meta_category, held, wording_matches, taken_at)
				VALUES (?, ?, 'UTILITY', ?, 'UTILITY', true, true, ?)
				""", temple, name, status, LONG_AGO);
	}

	/**
	 * The user row is read the way the security filter reads it: under the auth-lookup policy, because the
	 * platform operator has no temple and {@code users} shows a tenantless row to nobody else. The lookup
	 * context is cleared straight after, so no test runs with it set.
	 */
	private void signInAs(String firebaseUid) {
		TenantContext.setAuthLookupUid(firebaseUid);
		AuthenticatedUser actor;
		try {
			actor = new AuthenticatedUser(users.findByFirebaseUid(firebaseUid).orElseThrow());
		} finally {
			TenantContext.clear();
		}
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(actor, null, actor.getAuthorities()));
	}

	private static Set<String> currentAuthorities() {
		Set<String> names = new HashSet<>();
		for (GrantedAuthority authority : SecurityContextHolder.getContext().getAuthentication().getAuthorities()) {
			names.add(authority.getAuthority());
		}
		return names;
	}

	private static String rule(String method, Class<?>... parameters) throws NoSuchMethodException {
		PreAuthorize rule = OpsController.class.getMethod(method, parameters).getAnnotation(PreAuthorize.class);
		assertThat(rule).as(method + " declares a permission").isNotNull();
		return rule.value();
	}

	private JsonNode json(org.springframework.test.web.servlet.ResultActions result, int expectedStatus) throws Exception {
		MockHttpServletResponse response = result.andReturn().getResponse();
		String body = response.getContentAsString(StandardCharsets.UTF_8);
		responseBodies.add(body);
		assertThat(response.getStatus()).as(body).isEqualTo(expectedStatus);
		return objectMapper.readTree(body);
	}

	private int count(String sql, UUID id) {
		Integer n = admin.queryForObject(sql, Integer.class, id);
		return n == null ? 0 : n;
	}

	private static Map<String, JsonNode> byName(JsonNode view) {
		Map<String, JsonNode> rows = new LinkedHashMap<>();
		view.get("templates").forEach(row -> rows.put(row.get("name").asText(), row));
		return rows;
	}

	private static void assertRow(JsonNode row, String status, String metaCategory, boolean held, boolean wording) {
		String name = row.get("name").asText();
		assertThat(row.get("metaStatus").asText()).as(name + " metaStatus").isEqualTo(status);
		assertThat(row.get("metaCategory").asText()).as(name + " metaCategory").isEqualTo(metaCategory);
		assertThat(row.get("held").asBoolean()).as(name + " held").isEqualTo(held);
		assertThat(row.get("wordingMatches").asBoolean()).as(name + " wordingMatches").isEqualTo(wording);
	}

	private static Set<String> fieldNames(JsonNode node) {
		Set<String> names = new HashSet<>();
		node.fieldNames().forEachRemaining(names::add);
		return names;
	}

	private static List<String> allNames() {
		return Arrays.stream(NotificationTemplate.values()).map(NotificationTemplate::whatsappTemplateName).toList();
	}

	private static NotificationTemplate template(String name) {
		return Arrays.stream(NotificationTemplate.values())
				.filter(t -> t.whatsappTemplateName().equals(name)).findFirst().orElseThrow();
	}

	private static String body(String name) {
		return template(name).whatsappBodyText();
	}

	private static ch.qos.logback.classic.Logger root() {
		return (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
	}

	// ---- Meta, played by a local server that answers lookups and refuses everything else -------------

	static final class FakeMeta {

		record Held(String status, String category, String body, String rejectedReason) {
		}

		private final ObjectMapper json;
		private final HttpServer server;
		private final Map<String, Held> holds = new ConcurrentHashMap<>();
		private final Set<String> failing = ConcurrentHashMap.newKeySet();

		final AtomicInteger requests = new AtomicInteger();
		final AtomicInteger lookups = new AtomicInteger();
		final AtomicInteger posts = new AtomicInteger();
		final AtomicInteger otherRequests = new AtomicInteger();
		final List<String> lookupPaths = Collections.synchronizedList(new ArrayList<>());
		final List<String> authorizations = Collections.synchronizedList(new ArrayList<>());

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

		void hold(String name, String category, String status, String body, String rejectedReason) {
			holds.put(name, new Held(status, category, body, rejectedReason));
		}

		void holdEveryTemplateAsReleased(String status) {
			for (NotificationTemplate template : NotificationTemplate.values()) {
				hold(template.whatsappTemplateName(), template.whatsappCategory(), status, template.whatsappBodyText(), null);
			}
		}

		void forget(String name) {
			holds.remove(name);
		}

		void failFor(String name) {
			failing.add(name);
		}

		private void handle(HttpExchange exchange) throws IOException {
			requests.incrementAndGet();
			exchange.getRequestBody().readAllBytes();
			String method = exchange.getRequestMethod();
			String path = exchange.getRequestURI().getPath();
			authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
			int status;
			String body;
			if ("GET".equals(method) && path.endsWith("/message_templates")) {
				lookups.incrementAndGet();
				lookupPaths.add(path);
				String asked = Arrays.stream(exchange.getRequestURI().getRawQuery().split("&"))
						.filter(p -> p.startsWith("name="))
						.map(p -> URLDecoder.decode(p.substring(5), StandardCharsets.UTF_8))
						.findFirst().orElse("");
				if (failing.contains(asked)) {
					status = 500;
					body = "{\"error\":{\"message\":\"An unexpected error has occurred.\",\"type\":\"OAuthException\",\"code\":2}}";
				} else {
					List<Map<String, Object>> data = new ArrayList<>();
					Held held = holds.get(asked);
					if (held != null) {
						Map<String, Object> entry = new HashMap<>();
						entry.put("id", "id-" + asked);
						entry.put("name", asked);
						entry.put("language", "en");
						entry.put("status", held.status());
						entry.put("category", held.category());
						entry.put("components", List.of(Map.of("type", "BODY", "text", held.body())));
						if (held.rejectedReason() != null) {
							entry.put("rejected_reason", held.rejectedReason());
						}
						data.add(entry);
					}
					status = 200;
					body = json.writeValueAsString(Map.of("data", data));
				}
			} else if ("POST".equals(method)) {
				posts.incrementAndGet();
				status = 400;
				body = "{\"error\":{\"message\":\"This fake refuses every POST.\",\"code\":100}}";
			} else {
				otherRequests.incrementAndGet();
				status = 400;
				body = "{\"error\":{\"message\":\"Not expected.\",\"code\":100}}";
			}
			byte[] out = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, out.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(out);
			}
		}
	}
}
