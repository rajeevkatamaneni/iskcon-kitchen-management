package org.iskcon.kms.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
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
import java.util.stream.Collectors;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.error.GlobalExceptionHandler;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The read-only comparison of what Meta holds with what this release would send (T-173).
 *
 * <p><strong>What this class exists to prove is mostly what the endpoint never does.</strong> Reload
 * (T-169a) rewords any template whose body Meta holds differently from ours, and Meta allows one edit a
 * day. This endpoint is how anyone finds out, before pressing Reload on a real temple, whether Meta hands
 * bodies back exactly as registered. So it must not register, edit, or record anything, and each of
 * those is asserted here at the far side of the boundary it would cross:
 * <ul>
 *   <li><strong>No POST to Meta</strong>, counted at the HTTP stub, per test in {@link #tearDown} and for
 *       the whole class in {@link #noTestInThisClassPostedToMeta}. Registration and editing are both
 *       POSTs, and every one of {@code submitTemplates}, {@code bringUpToDate} and
 *       {@code reloadTemplates} POSTs a registration for every template.</li>
 *   <li><strong>No write to {@code tenant_settings}</strong>, compared column by column as the
 *       unprivileged app role, from a row seeded with a value in every column a template send writes, so
 *       a write cannot pass by writing what was already there.</li>
 *   <li><strong>The token never logged or returned</strong>: every log event of every test, and every
 *       response body, is searched for it, and the stub proves the token was really in use.</li>
 *   <li><strong>The settings service cannot be reached at all</strong>: the comparison's fields are
 *       pinned by reflection, and the controller under test is built with no settings service.</li>
 * </ul>
 *
 * <p><strong>Meta is a local {@link HttpServer}</strong>, as in {@link WhatsAppTemplateReloadIT}: the
 * production {@link MetaWhatsAppClient} is pointed at it, so the lookup URL, Meta's JSON and the BODY
 * parsing are all the real ones. Its lookup answer has the shape Meta's Graph API reference gives for
 * {@code GET /{WABA_ID}/message_templates} (cited in {@link MetaWhatsAppClient#findTemplate}). Its name
 * filter matches part of a name on purpose, as {@code WhatsAppTemplateReloadIT}'s does, so asking about
 * {@code shift_reminder} also lists {@code volunteer_shift_reminder}. Unlike that fake it holds nothing
 * that a request can change: every POST is counted and refused.
 *
 * <p>Nothing leaves the building, and no Spring bean that talks to Meta is used. The comparison is built
 * by hand and wrapped in the application's own transaction interceptor, and the class carries no
 * {@code @MockBean}, {@code @Import} or {@code @TestPropertySource}, so it shares the suite's context.
 */
class WhatsAppTemplateComparisonIT extends AbstractIntegrationTest {

	/** A token shaped like Meta's, unique enough that finding it anywhere means it leaked. */
	private static final String TOKEN = "EAAGt173NeverShownOrLoggedZq8x";

	private static final String ENDPOINT = "/api/v1/settings/whatsapp/templates/meta-comparison";

	private static final OffsetDateTime SENT_ON = OffsetDateTime.of(2026, 9, 13, 2, 51, 0, 0, ZoneOffset.UTC);

	/** Every POST the stub received, summed over every test in this class. */
	private static final AtomicInteger POSTS_ACROSS_THE_CLASS = new AtomicInteger();

	private static final Set<String> ENTRY_FIELDS = Set.of("name", "ourCategory", "metaCategory", "metaStatus",
			"held", "bodyMatchesExactly", "bodyMatchesAfterTrim", "metaBody", "ourBody", "lookupProblem",
			// T-178: Meta's rejected_reason, carried for the operator's stored copy.
			"metaRejectedReason");

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TenantSecretStore secrets;

	@Autowired
	private UserRepository users;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private JdbcTemplate admin;
	private UUID govinda;
	private FakeMeta meta;
	private WhatsAppTemplateComparison comparison;
	private MockMvc mvc;
	private ListAppender<ILoggingEvent> logs;
	private final Map<String, Level> levelsBefore = new LinkedHashMap<>();
	private final List<String> responseBodies = new ArrayList<>();

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
				VALUES (?, 'uid-admin-t173', 'Temple Admin', 'admin-t173@example.com', '+919876500173',
						'TEMPLE_ADMIN', 'ACTIVE'),
					   (?, 'uid-volunteer-t173', 'A Volunteer', 'volunteer-t173@example.com', '+919876500174',
						'VOLUNTEER', 'ACTIVE')
				""", govinda, govinda);

		// Every log line of every test is kept, DEBUG included for the two classes that talk to Meta, so
		// the token can be searched for in all of it.
		logs = new ListAppender<>();
		logs.start();
		root().addAppender(logs);
		for (Class<?> talksToMeta : List.of(MetaWhatsAppClient.class, WhatsAppTemplateComparison.class)) {
			ch.qos.logback.classic.Logger logger = logger(talksToMeta);
			levelsBefore.put(talksToMeta.getName(), logger.getLevel());
			logger.setLevel(Level.DEBUG);
		}

		meta = new FakeMeta(objectMapper);
		WhatsAppTemplateComparison target =
				new WhatsAppTemplateComparison(jdbc, secrets, new MetaWhatsAppClient(objectMapper, meta.url()));
		ProxyFactory proxy = new ProxyFactory(target);
		proxy.setProxyTargetClass(true);
		proxy.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
		comparison = (WhatsAppTemplateComparison) proxy.getProxy();
		mvc = mvcFor(controller());

		TenantContext.set(govinda);
		signInAs("uid-admin-t173");
	}

	/**
	 * The stub is stopped and the row, the secrets and the loggers put back before anything is asserted,
	 * so a failure here never leaves state for the next test. Then: no POST, and the token nowhere.
	 */
	@AfterEach
	void tearDown() {
		meta.stop();
		int posts = meta.posts.get();
		int otherWrites = meta.nonGetRequests.get() - posts;
		POSTS_ACROSS_THE_CLASS.addAndGet(posts);
		root().detachAppender(logs);
		levelsBefore.forEach((name, level) -> ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(name)).setLevel(level));
		SecurityContextHolder.clearContext();
		TenantContext.clear();
		secrets.deleteAll(govinda);
		admin.execute("DELETE FROM tenant_settings");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");

		assertThat(posts).as("POSTs this test sent to Meta").isZero();
		assertThat(otherWrites).as("requests to Meta that were neither GET nor POST").isZero();
		for (ILoggingEvent event : logs.list) {
			String line = event.getFormattedMessage()
					+ (event.getThrowableProxy() == null ? "" : ThrowableProxyUtil.asString(event.getThrowableProxy()));
			assertThat(line).as("a log line from " + event.getLoggerName()).doesNotContain(TOKEN);
		}
		for (String body : responseBodies) {
			assertThat(body).as("a response body").doesNotContain(TOKEN);
		}
	}

	/** Across the whole class, counted at the stub. */
	@AfterAll
	static void noTestInThisClassPostedToMeta() {
		assertThat(POSTS_ACROSS_THE_CLASS.get()).as("POSTs to Meta across WhatsAppTemplateComparisonIT").isZero();
	}

	// ---- wiring --------------------------------------------------------------------------------------

	/**
	 * Built with no settings service and no email service on purpose: the endpoint must need neither,
	 * and if it reached for the settings service this test class would fail with a null, not quietly
	 * call it.
	 */
	private WhatsAppSettingsController controller() {
		return new WhatsAppSettingsController(null, null, comparison);
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

	private static ch.qos.logback.classic.Logger root() {
		return (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
	}

	private static ch.qos.logback.classic.Logger logger(Class<?> type) {
		return (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(type);
	}

	// ---- the temple ----------------------------------------------------------------------------------

	/**
	 * A connected temple whose row has a value in every column a template send writes: a date, a
	 * refused list, fingerprints (one of them null, as V129 allows) and the account sent to. Seeded by
	 * SQL rather than by a Save, because a Save registers templates, and that is a POST to Meta this
	 * class promises never happens.
	 */
	private void aConnectedTemple() throws Exception {
		Map<String, String> fingerprints = new LinkedHashMap<>();
		for (NotificationTemplate template : NotificationTemplate.values()) {
			fingerprints.put(template.whatsappTemplateName(), template.whatsappFingerprint("en"));
		}
		fingerprints.put("shift_reminder", null);
		String refused = objectMapper.writeValueAsString(List.of(Map.of("name", "donation_thank_you",
				"reason", TenantWhatsAppSettingsService.heldUnderReason("MARKETING"),
				"kind", "HELD_UNDER_ANOTHER_CATEGORY")));
		jdbc.update("""
				INSERT INTO tenant_settings (tenant_id, whatsapp_phone_number_id, whatsapp_waba_id,
						whatsapp_webhook_token, whatsapp_display_number, whatsapp_verified_at,
						whatsapp_templates_submitted_at, whatsapp_refused_templates, whatsapp_template_fingerprints,
						whatsapp_templates_sent_waba_id, whatsapp_templates_sent_phone_number_id, updated_at)
				VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, 'phone-govinda', 'waba-govinda',
						'webhook-token-t173', 'Temple Kitchen (+1 555-010-0173)', ?, ?, ?::jsonb, ?::jsonb,
						'waba-govinda', 'phone-govinda', ?)
				""", SENT_ON, SENT_ON, refused, objectMapper.writeValueAsString(fingerprints), SENT_ON);
		secrets.put(govinda, TenantSecretStore.Kind.WHATSAPP_ACCESS_TOKEN, TOKEN);
	}

	/** Every kind of answer at once, on six different templates. */
	private void metaGivesEveryKindOfAnswer() {
		meta.holdEveryTemplateAsReleased();
		meta.hold("po_delivery", "UTILITY", "APPROVED", " " + body("po_delivery") + "\n");
		meta.hold("shift_broadcast", "UTILITY", "APPROVED", body("shift_broadcast").replaceFirst(" ", "  "));
		meta.forget("shift_reminder");
		meta.hold("donation_thank_you", "MARKETING", "APPROVED", body("donation_thank_you"));
		meta.dropConnectionFor("low_stock_digest");
		meta.answerWithAnErrorFor("temple_announcement");
	}

	private static NotificationTemplate template(String name) {
		return Arrays.stream(NotificationTemplate.values())
				.filter(t -> t.whatsappTemplateName().equals(name)).findFirst().orElseThrow();
	}

	private static String body(String name) {
		return template(name).whatsappBodyText();
	}

	// ---- the request and its answer -----------------------------------------------------------------

	private MockHttpServletResponse compare(MockMvc through) throws Exception {
		MockHttpServletResponse response = through.perform(get(ENDPOINT)).andReturn().getResponse();
		responseBodies.add(response.getContentAsString());
		return response;
	}

	private JsonNode compared() throws Exception {
		MockHttpServletResponse response = compare(mvc);
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
		return objectMapper.readTree(response.getContentAsString());
	}

	private static Map<String, JsonNode> byName(JsonNode report) {
		Map<String, JsonNode> entries = new LinkedHashMap<>();
		report.get("templates").forEach(e -> entries.put(e.get("name").asText(), e));
		return entries;
	}

	private static Set<String> fieldNames(JsonNode node) {
		Set<String> names = new HashSet<>();
		node.fieldNames().forEachRemaining(names::add);
		return names;
	}

	/** Every column of this temple's row as text, read as the app role through the RLS policy. */
	private Map<String, String> theRowAsTheAppRole() {
		List<String> columns = jdbc.queryForList("""
				SELECT column_name FROM information_schema.columns
				WHERE table_schema = 'public' AND table_name = 'tenant_settings' ORDER BY ordinal_position
				""", String.class);
		String select = columns.stream().map(c -> "\"" + c + "\"::text AS \"" + c + "\"").collect(Collectors.joining(", "));
		List<Map<String, Object>> rows = jdbc.queryForList("SELECT " + select + " FROM tenant_settings");
		assertThat(rows).as("the app role sees exactly this temple's row").hasSize(1);
		Map<String, String> row = new TreeMap<>();
		rows.get(0).forEach((column, value) -> row.put(column, value == null ? "<null>" : value.toString()));
		return row;
	}

	// ---- what it answers -----------------------------------------------------------------------------

	@Test
	@DisplayName("all twenty held and identical: every entry matches exactly, shows no bodies, and was asked with the stored token")
	void allHeldAndIdentical() throws Exception {
		aConnectedTemple();
		meta.holdEveryTemplateAsReleased();

		JsonNode report = compared();

		assertThat(report.get("wabaId").asText()).isEqualTo("waba-govinda");
		assertThat(report.get("language").asText()).isEqualTo("en");
		assertThat(NotificationTemplate.values()).hasSize(20);
		assertThat(byName(report).keySet()).containsExactly(Arrays.stream(NotificationTemplate.values())
				.map(NotificationTemplate::whatsappTemplateName).toArray(String[]::new));
		for (JsonNode entry : report.get("templates")) {
			String name = entry.get("name").asText();
			// Keys first: an absent field and a null read alike to a matcher that checks values only.
			assertThat(fieldNames(entry)).as(name).isEqualTo(ENTRY_FIELDS);
			assertThat(entry.get("held").isBoolean() && entry.get("held").asBoolean()).as(name + " held").isTrue();
			assertThat(entry.get("bodyMatchesExactly").asBoolean()).as(name + " exact").isTrue();
			assertThat(entry.get("bodyMatchesAfterTrim").asBoolean()).as(name + " after trim").isTrue();
			assertThat(entry.get("metaBody").isNull()).as(name + " metaBody").isTrue();
			assertThat(entry.get("ourBody").isNull()).as(name + " ourBody").isTrue();
			assertThat(entry.get("lookupProblem").isNull()).as(name + " lookupProblem").isTrue();
			assertThat(entry.get("metaStatus").asText()).isEqualTo("APPROVED");
			assertThat(entry.get("metaCategory").asText()).isEqualTo(entry.get("ourCategory").asText())
					.isEqualTo(template(name).whatsappCategory());
		}

		// The stub counts, and the token was in use: without these the zero-POST and never-logged checks
		// in tearDown could pass against a stub that saw nothing.
		assertThat(meta.lookups.get()).as("one lookup per template").isEqualTo(20);
		assertThat(meta.lookedUpNames).containsExactlyInAnyOrderElementsOf(byName(report).keySet());
		assertThat(meta.authorizations).containsOnly("Bearer " + TOKEN);
		assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage))
				.anyMatch(line -> line.startsWith("Compared 20 WhatsApp templates with Meta for temple " + govinda
						+ ": 20 identical, 0 identical only after trimming, 0 worded differently, 0 not held, 0 not answered"));
	}

	@Test
	@DisplayName("a body differing only in outer whitespace matches after trimming but not exactly, and Meta's body is shown as sent")
	void outerWhitespaceOnly() throws Exception {
		aConnectedTemple();
		meta.holdEveryTemplateAsReleased();
		String padded = " " + body("po_delivery") + "\n";
		meta.hold("po_delivery", "UTILITY", "APPROVED", padded);

		JsonNode entry = byName(compared()).get("po_delivery");

		assertThat(entry.get("held").asBoolean()).isTrue();
		assertThat(entry.get("bodyMatchesExactly").asBoolean()).isFalse();
		assertThat(entry.get("bodyMatchesAfterTrim").asBoolean()).isTrue();
		// Exactly as Meta sent it: the leading space and trailing newline survive findTemplate's parsing.
		assertThat(entry.get("metaBody").asText()).isEqualTo(padded);
		assertThat(entry.get("ourBody").asText()).isEqualTo(body("po_delivery"));
	}

	@Test
	@DisplayName("a body differing inside matches neither way, and both bodies are shown exactly")
	void differsInside() throws Exception {
		aConnectedTemple();
		meta.holdEveryTemplateAsReleased();
		String normalised = body("shift_broadcast").replaceFirst(" ", "  ");
		assertThat(normalised).isNotEqualTo(body("shift_broadcast"));
		meta.hold("shift_broadcast", "UTILITY", "APPROVED", normalised);

		Map<String, JsonNode> entries = byName(compared());
		JsonNode entry = entries.get("shift_broadcast");

		assertThat(entry.get("held").asBoolean()).isTrue();
		assertThat(entry.get("bodyMatchesExactly").asBoolean()).isFalse();
		assertThat(entry.get("bodyMatchesAfterTrim").asBoolean()).isFalse();
		assertThat(entry.get("metaBody").asText()).isEqualTo(normalised);
		assertThat(entry.get("ourBody").asText()).isEqualTo(body("shift_broadcast"));
		assertThat(entries.values().stream().filter(e -> e.get("bodyMatchesExactly").asBoolean()).count())
				.as("the other nineteen are untouched by one difference").isEqualTo(19);
	}

	@Test
	@DisplayName("a template Meta does not hold reads held false, even when Meta lists a longer name containing it")
	void notHeld() throws Exception {
		aConnectedTemple();
		meta.holdEveryTemplateAsReleased();
		meta.forget("shift_reminder");
		assertThat(meta.held("volunteer_shift_reminder")).as("the partial match the lookup will also list").isNotNull();

		JsonNode entry = byName(compared()).get("shift_reminder");

		assertThat(entry.get("held").isBoolean()).isTrue();
		assertThat(entry.get("held").asBoolean()).isFalse();
		for (String absent : List.of("metaCategory", "metaStatus", "bodyMatchesExactly", "bodyMatchesAfterTrim",
				"metaBody", "ourBody", "lookupProblem", "metaRejectedReason")) {
			assertThat(entry.get(absent).isNull()).as(absent).isTrue();
		}
	}

	@Test
	@DisplayName("a template held under another category shows both categories, and its body still compares")
	void heldUnderAnotherCategory() throws Exception {
		aConnectedTemple();
		meta.holdEveryTemplateAsReleased();
		assertThat(template("donation_thank_you").whatsappCategory()).isNotEqualTo("MARKETING");
		meta.hold("donation_thank_you", "MARKETING", "APPROVED", body("donation_thank_you"));

		JsonNode entry = byName(compared()).get("donation_thank_you");

		assertThat(entry.get("ourCategory").asText()).isEqualTo(template("donation_thank_you").whatsappCategory());
		assertThat(entry.get("metaCategory").asText()).isEqualTo("MARKETING");
		assertThat(entry.get("held").asBoolean()).isTrue();
		assertThat(entry.get("bodyMatchesExactly").asBoolean()).isTrue();
	}

	@Test
	@DisplayName("Meta unreachable for one template, and answering with an error for another: those entries say so, and the rest still answer")
	void metaFailsForOneTemplate() throws Exception {
		aConnectedTemple();
		meta.holdEveryTemplateAsReleased();
		meta.dropConnectionFor("low_stock_digest");
		meta.answerWithAnErrorFor("temple_announcement");

		Map<String, JsonNode> entries = byName(compared());

		JsonNode unreachable = entries.get("low_stock_digest");
		assertThat(unreachable.get("held").isNull()).as("not known, so not false").isTrue();
		assertThat(unreachable.get("bodyMatchesExactly").isNull()).isTrue();
		assertThat(unreachable.get("lookupProblem").asText()).isEqualTo(WhatsAppTemplateComparison.NOT_REACHED);

		JsonNode refused = entries.get("temple_announcement");
		assertThat(refused.get("held").isNull()).isTrue();
		assertThat(refused.get("lookupProblem").asText()).isEqualTo(WhatsAppTemplateComparison.META_ANSWERED_WITH_AN_ERROR);

		assertThat(entries.values().stream().filter(e -> e.get("lookupProblem").isNull()
				&& e.get("bodyMatchesExactly").asBoolean()).count()).as("the other eighteen").isEqualTo(18);
	}

	// ---- when it cannot answer -----------------------------------------------------------------------

	@Test
	@DisplayName("a temple that has not connected gets KMS-400001 naming phoneNumberId, and Meta is never asked")
	void notConnected() throws Exception {
		MockHttpServletResponse response = compare(mvc);

		assertThat(response.getStatus()).isEqualTo(400);
		JsonNode error = objectMapper.readTree(response.getContentAsString());
		assertThat(error.get("code").asText()).isEqualTo(ErrorCode.VALIDATION_FAILED.reference()).isEqualTo("KMS-400001");
		assertThat(meta.allRequests.get()).as("requests to Meta").isZero();
		// The field travels in the exception's context, which goes to the log and never to the client,
		// exactly as for Test and Reload.
		assertThat(logs.list.stream().filter(e -> e.getLoggerName().equals(GlobalExceptionHandler.class.getName()))
				.map(ILoggingEvent::getFormattedMessage))
				.anyMatch(line -> line.startsWith("KMS-400001") && line.contains("context={field=phoneNumberId}"));
	}

	/**
	 * Through Spring Security's own {@code @PreAuthorize} interceptor, and the same proxy is shown letting
	 * a Temple Admin through, so the 403 is the permission and not a proxy that refuses everyone.
	 */
	@Test
	@DisplayName("a caller without MANAGE_TEMPLE_SETTINGS gets 403, and Meta is never asked")
	void needsManageTempleSettings() throws Exception {
		PreAuthorize rule = WhatsAppSettingsController.class.getMethod("compareTemplatesWithMeta")
				.getAnnotation(PreAuthorize.class);
		assertThat(rule).isNotNull();
		assertThat(rule.value()).isEqualTo("hasAuthority('MANAGE_TEMPLE_SETTINGS')");

		aConnectedTemple();
		meta.holdEveryTemplateAsReleased();
		ProxyFactory secured = new ProxyFactory(controller());
		secured.setProxyTargetClass(true);
		secured.addAdvisor(AuthorizationManagerBeforeMethodInterceptor.preAuthorize());
		MockMvc securedMvc = mvcFor((WhatsAppSettingsController) secured.getProxy());

		signInAs("uid-volunteer-t173");
		MockHttpServletResponse refused = compare(securedMvc);
		assertThat(refused.getStatus()).isEqualTo(403);
		assertThat(objectMapper.readTree(refused.getContentAsString()).get("code").asText())
				.isEqualTo(ErrorCode.NOT_PERMITTED.reference());
		assertThat(meta.allRequests.get()).as("requests to Meta for a refused caller").isZero();

		signInAs("uid-admin-t173");
		assertThat(compare(securedMvc).getStatus()).isEqualTo(200);
		assertThat(meta.lookups.get()).isEqualTo(20);
	}

	// ---- what it never does --------------------------------------------------------------------------

	/**
	 * The row is read before and after, column by column, as the app role. Every kind of answer is in
	 * play, including the differing bodies Reload would act on, so a comparison that recorded anything
	 * about them would have something to record. The status is asserted last on purpose: a write that
	 * PostgreSQL refuses fails the request, and this test should show whether the row moved before it
	 * says that.
	 */
	@Test
	@DisplayName("the tenant_settings row is identical before and after, column by column, as the app role")
	void theRowIsUnchanged() throws Exception {
		aConnectedTemple();
		metaGivesEveryKindOfAnswer();
		Map<String, String> before = theRowAsTheAppRole();
		for (String written : List.of("whatsapp_template_fingerprints", "whatsapp_refused_templates",
				"whatsapp_templates_submitted_at", "whatsapp_templates_sent_waba_id",
				"whatsapp_templates_sent_phone_number_id", "whatsapp_verified_at", "updated_at")) {
			assertThat(before).as("seeded, so a write to it can show").containsKey(written);
			assertThat(before.get(written)).as(written).isNotIn("<null>", "{}", "[]");
		}

		MockHttpServletResponse response = compare(mvc);

		assertThat(theRowAsTheAppRole()).isEqualTo(before);
		assertThat(admin.queryForObject("SELECT count(*) FROM audit_events WHERE tenant_id = ?", Integer.class, govinda))
				.as("audit entries").isZero();
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
		assertThat(meta.lookups.get()).as("every template was asked about").isGreaterThanOrEqualTo(20);
	}

	@Test
	@DisplayName("the comparison holds only a JdbcTemplate, the secret store and the Meta client, so no template-sending path can be reached from it")
	void cannotReachAnythingThatSends() {
		Set<Class<?>> held = Arrays.stream(WhatsAppTemplateComparison.class.getDeclaredFields())
				.filter(f -> !Modifier.isStatic(f.getModifiers()))
				.map(Field::getType)
				.collect(Collectors.toSet());
		assertThat(held).containsExactlyInAnyOrder(JdbcTemplate.class, TenantSecretStore.class, MetaWhatsAppClient.class);
		assertThat(WhatsAppTemplateComparison.class.getDeclaredConstructors()).allSatisfy(constructor ->
				assertThat(constructor.getParameterTypes()).doesNotContain(TenantWhatsAppSettingsService.class));
	}

	/**
	 * Two halves. The method is declared read-only; and, through the application's own transaction
	 * manager, a read-only transaction is one PostgreSQL itself refuses to write in. The second half is the
	 * one that matters: without it, {@code readOnly} could be a hint Hibernate keeps to itself.
	 */
	@Test
	@DisplayName("compare runs in a read-only transaction, which PostgreSQL itself refuses to write in")
	void theDatabaseRefusesAWrite() throws Exception {
		Transactional declared = WhatsAppTemplateComparison.class.getMethod("compare").getAnnotation(Transactional.class);
		assertThat(declared).isNotNull();
		assertThat(declared.readOnly()).isTrue();

		aConnectedTemple();
		TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
		readOnly.setReadOnly(true);
		String readOnlyFlag = readOnly.execute(s -> jdbc.queryForObject("SHOW transaction_read_only", String.class));
		assertThat(readOnlyFlag).isEqualTo("on");
		assertThatThrownBy(() -> readOnly.executeWithoutResult(s -> jdbc.update("""
				UPDATE tenant_settings SET whatsapp_template_fingerprints = '{}'::jsonb
				WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""")))
				.hasMessageContaining("read-only transaction");
	}

	// ---- Meta, played by a local server that nothing can change --------------------------------------

	/** One answer from Meta. */
	private record MetaAnswer(int status, String body) {
	}

	/** A fake Graph API that lists templates and refuses, and counts, everything else. */
	static final class FakeMeta {

		/** Meta's transient "unexpected error" envelope, code 2, as its error codes page lists it. */
		private static final MetaAnswer UNEXPECTED_ERROR = new MetaAnswer(500, """
				{"error":{"message":"An unexpected error has occurred. Please retry your request later.",
				"type":"OAuthException","is_transient":true,"code":2,"fbtrace_id":"AQt173aaaa"}}
				""");

		record Held(String id, String name, String category, String status, String body) {
		}

		private final ObjectMapper json;
		private final HttpServer server;
		private final Map<String, Held> holds = new ConcurrentHashMap<>();
		private final Set<String> dropped = ConcurrentHashMap.newKeySet();
		private final Set<String> erroring = ConcurrentHashMap.newKeySet();

		final AtomicInteger allRequests = new AtomicInteger();
		final AtomicInteger nonGetRequests = new AtomicInteger();
		final AtomicInteger posts = new AtomicInteger();
		final AtomicInteger lookups = new AtomicInteger();
		final List<String> lookedUpNames = Collections.synchronizedList(new ArrayList<>());
		final Set<String> authorizations = ConcurrentHashMap.newKeySet();

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

		void dropConnectionFor(String name) {
			dropped.add(name);
		}

		void answerWithAnErrorFor(String name) {
			erroring.add(name);
		}

		private void handle(HttpExchange exchange) throws IOException {
			allRequests.incrementAndGet();
			String method = exchange.getRequestMethod();
			authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
			exchange.getRequestBody().readAllBytes();
			if (!"GET".equals(method)) {
				nonGetRequests.incrementAndGet();
				if ("POST".equals(method)) {
					posts.incrementAndGet();
				}
				answer(exchange, new MetaAnswer(400, "{\"error\":{\"message\":\"Unsupported post request.\",\"code\":100}}"));
				return;
			}
			if (!exchange.getRequestURI().getPath().endsWith("/message_templates")) {
				answer(exchange, new MetaAnswer(200, "{\"display_phone_number\":\"+1 555-010-0173\"}"));
				return;
			}
			lookups.incrementAndGet();
			String asked = Arrays.stream(exchange.getRequestURI().getRawQuery().split("&"))
					.filter(p -> p.startsWith("name="))
					.map(p -> URLDecoder.decode(p.substring(5), StandardCharsets.UTF_8))
					.findFirst().orElse("");
			lookedUpNames.add(asked);
			if (dropped.contains(asked)) {
				// No status line, no headers: the connection simply ends, which the client sees as an IOException.
				exchange.close();
				return;
			}
			if (erroring.contains(asked)) {
				answer(exchange, UNEXPECTED_ERROR);
				return;
			}
			// Part of a name matches, on purpose, and longer names first, so an exact match is never simply
			// the first entry: see the class comment.
			List<Map<String, Object>> data = holds.values().stream()
					.filter(h -> h.name().contains(asked))
					.sorted((a, b) -> b.name().length() - a.name().length())
					.map(h -> {
						Map<String, Object> entry = new LinkedHashMap<>();
						entry.put("id", h.id());
						entry.put("name", h.name());
						entry.put("language", "en");
						entry.put("status", h.status());
						entry.put("category", h.category());
						entry.put("components", List.of(Map.of("type", "BODY", "text", h.body())));
						return entry;
					})
					.collect(Collectors.toList());
			answer(exchange, new MetaAnswer(200, json.writeValueAsString(Map.of("data", data))));
		}

		private static void answer(HttpExchange exchange, MetaAnswer answer) throws IOException {
			byte[] out = answer.body().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
			exchange.sendResponseHeaders(answer.status(), out.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(out);
			}
		}
	}
}
