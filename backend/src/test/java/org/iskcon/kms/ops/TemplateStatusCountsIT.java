package org.iskcon.kms.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.error.GlobalExceptionHandler;
import org.iskcon.kms.notification.NotificationTemplate;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * {@code GET /api/v1/ops/whatsapp-templates/status-counts}: each template's status counted across every
 * temple's stored copy (T-178).
 *
 * <p><strong>The fixture is four temples, seeded as the superuser with known answers,</strong> so every
 * number is asserted against a count a reader can do by hand:
 * <ul>
 *   <li>Radha Govinda: volunteer_shift_reminder APPROVED; donation_thank_you PENDING as MARKETING; leave_revoked
 *       REJECTED for ABUSIVE_CONTENT, which is a refusal but not of formatting.</li>
 *   <li>Krishna Balaram: volunteer_shift_reminder REJECTED for INVALID_FORMAT; donation_thank_you IN_APPEAL as
 *       MARKETING.</li>
 *   <li>Jagannath: volunteer_shift_reminder PAUSED; donation_thank_you not held; po_delivery not answered.</li>
 *   <li>Gaura Nitai: no copy at all, as a temple with no WhatsApp connection has.</li>
 * </ul>
 *
 * <p><strong>Meta is not involved, and cannot be.</strong> The service under test is built with no
 * comparison at all, so a count that tried to ask Meta would fail with a null rather than quietly make a
 * call. Counts read only what temples have stored.
 */
class TemplateStatusCountsIT extends AbstractIntegrationTest {

	private static final Set<String> COUNT_FIELDS = Set.of("name", "templesCounted", "approved", "pending",
			"refused", "marketing", "formattingRefusal");

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private AuditService auditService;

	@Autowired
	private UserRepository users;

	@Autowired
	private ObjectMapper objectMapper;

	private JdbcTemplate admin;
	private final Map<String, UUID> temples = new LinkedHashMap<>();
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		TenantContext.clear();
		admin = new JdbcTemplate(adminDataSource());
		temples.put("radha-govinda-t178c|Sri Sri Radha Govinda Temple", null);
		temples.put("krishna-balaram-t178c|Sri Krishna Balaram Temple", null);
		temples.put("jagannath-t178c|Sri Jagannath Mandir", null);
		temples.put("gaura-nitai-t178c|Sri Sri Gaura Nitai Temple", null);
		for (String key : new ArrayList<>(temples.keySet())) {
			String[] parts = key.split("\\|");
			temples.put(key, admin.queryForObject("""
					INSERT INTO tenants (slug, name, latitude, longitude, timezone)
					VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata')
					RETURNING id
					""", UUID.class, parts[0], parts[1]));
		}
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (NULL, 'uid-super-t178c', 'Platform Operator', 'super-t178c@example.com', '+919000001178',
						'SUPER_ADMIN', 'ACTIVE'),
					   (?, 'uid-admin-t178c', 'Temple Admin', 'admin-t178c@example.com', '+919876501178',
						'TEMPLE_ADMIN', 'ACTIVE')
				""", temple(0));

		OpsService ops = new OpsService(jdbc, null, new TemplateStatusCopy(jdbc, auditService));
		ProxyFactory secured = new ProxyFactory(new OpsController(ops, new WhatsAppTemplateCatalogue(jdbc)));
		secured.setProxyTargetClass(true);
		secured.addAdvisor(AuthorizationManagerBeforeMethodInterceptor.preAuthorize());
		mvc = MockMvcBuilders.standaloneSetup(secured.getProxy())
				.setControllerAdvice(new GlobalExceptionHandler())
				.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
				.setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
				.build();
	}

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
		TenantContext.clear();
		admin.execute("DELETE FROM whatsapp_template_status_copy");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("counts are built from each temple's copy: approved, pending, refused, marketing, the temples counted, and a formatting refusal in one temple flags the template")
	void countsAcrossThreeTemplesCopies() throws Exception {
		seedTheFourTemples();
		signInAs("uid-super-t178c");

		MockHttpServletResponse response = mvc.perform(get("/api/v1/ops/whatsapp-templates/status-counts"))
				.andReturn().getResponse();
		String body = response.getContentAsString(StandardCharsets.UTF_8);
		assertThat(response.getStatus()).as(body).isEqualTo(200);
		JsonNode counts = objectMapper.readTree(body);

		NotificationTemplate[] all = NotificationTemplate.values();
		assertThat(counts.size()).as("one entry per template this release sends").isEqualTo(all.length);
		Map<String, JsonNode> byName = new LinkedHashMap<>();
		for (int i = 0; i < all.length; i++) {
			JsonNode entry = counts.get(i);
			assertThat(entry.get("name").asText()).as("in the release's order").isEqualTo(all[i].whatsappTemplateName());
			assertThat(fieldNames(entry)).as("counts only, no field that could carry a temple").isEqualTo(COUNT_FIELDS);
			byName.put(entry.get("name").asText(), entry);
		}

		// Govinda APPROVED, Krishna REJECTED for formatting, Jagannath PAUSED. Gaura Nitai has no copy.
		assertCounts(byName.get("volunteer_shift_reminder"), 3, 1, 0, 2, 0, true);
		// Govinda PENDING and Krishna IN_APPEAL, both MARKETING; Jagannath's copy says Meta does not hold it.
		assertCounts(byName.get("donation_thank_you"), 3, 0, 2, 0, 2, false);
		// Only Jagannath's copy has it, and Meta did not answer: counted, and in no group.
		assertCounts(byName.get("po_delivery"), 1, 0, 0, 0, 0, false);
		// Refused for its content, not its formatting: refused, not flagged.
		assertCounts(byName.get("leave_revoked"), 1, 0, 0, 1, 0, false);
		// In nobody's copy.
		assertCounts(byName.get("donation_receipt"), 0, 0, 0, 0, 0, false);

		// No response lists a temple: not by id, not by name, not by web address.
		for (Map.Entry<String, UUID> temple : temples.entrySet()) {
			String[] parts = temple.getKey().split("\\|");
			assertThat(body).doesNotContain(temple.getValue().toString()).doesNotContain(parts[0]).doesNotContain(parts[1]);
		}
		assertThat(TenantContext.get()).as("the operator's context is left as it was found").isEmpty();
	}

	/**
	 * Why the counts adopt each temple's context in turn rather than reading the table once: as the
	 * application role there is no such read. Without a temple's context the policy shows nothing at all,
	 * so a single cross-temple query would count zero everywhere.
	 */
	@Test
	@DisplayName("as kms_app the copy reads nothing without a temple's context, and only that temple's rows with one")
	void theCopyIsReadOneTempleAtATime() {
		seedTheFourTemples();

		assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
		assertThat(admin.queryForObject("SELECT count(*) FROM whatsapp_template_status_copy", Integer.class))
				.as("rows that exist").isEqualTo(8);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM whatsapp_template_status_copy", Integer.class))
				.as("rows the app role sees with no temple").isZero();
		TenantContext.set(temple(1));
		try {
			assertThat(jdbc.queryForList("SELECT meta_status FROM whatsapp_template_status_copy ORDER BY meta_status", String.class))
					.as("Krishna Balaram's own two rows").containsExactly("IN_APPEAL", "REJECTED");
		} finally {
			TenantContext.clear();
		}
	}

	@Test
	@DisplayName("a temple admin is refused the counts")
	void aTempleAdminIsRefused() throws Exception {
		seedTheFourTemples();
		signInAs("uid-admin-t178c");

		MockHttpServletResponse refused = mvc.perform(get("/api/v1/ops/whatsapp-templates/status-counts"))
				.andReturn().getResponse();

		assertThat(refused.getStatus()).isEqualTo(403);
		assertThat(objectMapper.readTree(refused.getContentAsString()).get("code").asText())
				.isEqualTo(ErrorCode.NOT_PERMITTED.reference());
	}

	// ---- helpers ------------------------------------------------------------------------------------

	private void seedTheFourTemples() {
		UUID govinda = temple(0);
		UUID krishna = temple(1);
		UUID jagannath = temple(2);
		seed(govinda, "volunteer_shift_reminder", "APPROVED", "UTILITY", true, null, null);
		seed(govinda, "donation_thank_you", "PENDING", "MARKETING", true, null, null);
		seed(govinda, "leave_revoked", "REJECTED", "UTILITY", true, "ABUSIVE_CONTENT", null);
		seed(krishna, "volunteer_shift_reminder", "REJECTED", "UTILITY", true, "INVALID_FORMAT", null);
		seed(krishna, "donation_thank_you", "IN_APPEAL", "MARKETING", true, null, null);
		seed(jagannath, "volunteer_shift_reminder", "PAUSED", "UTILITY", true, "NONE", null);
		seed(jagannath, "donation_thank_you", null, null, false, null, null);
		seed(jagannath, "po_delivery", null, null, null, null, "Meta could not be reached for this message.");
	}

	private void seed(UUID temple, String name, String status, String category, Boolean held, String reason,
			String problem) {
		admin.update("""
				INSERT INTO whatsapp_template_status_copy (tenant_id, template_name, our_category, meta_status,
				    meta_category, held, wording_matches, meta_rejected_reason, lookup_problem)
				VALUES (?, ?, 'UTILITY', ?, ?, ?, ?, ?, ?)
				""", temple, name, status, category, held, Boolean.TRUE.equals(held) ? Boolean.TRUE : null, reason,
				problem);
	}

	private UUID temple(int index) {
		return new ArrayList<>(temples.values()).get(index);
	}

	/** Read under the auth-lookup policy, as the security filter does, then cleared. */
	private void signInAs(String firebaseUid) {
		TenantContext.setAuthLookupUid(firebaseUid);
		AuthenticatedUser actor;
		try {
			// No temple to filter by here: the operator has none. Each fixture uid holds one account.
			var accounts = users.findAllByFirebaseUid(firebaseUid);
			assertThat(accounts).as("accounts held by " + firebaseUid).hasSize(1);
			actor = new AuthenticatedUser(accounts.get(0));
		} finally {
			TenantContext.clear();
		}
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(actor, null, actor.getAuthorities()));
	}

	private static void assertCounts(JsonNode entry, int counted, int approved, int pending, int refused,
			int marketing, boolean formatting) {
		String name = entry.get("name").asText();
		assertThat(entry.get("templesCounted").asInt()).as(name + " templesCounted").isEqualTo(counted);
		assertThat(entry.get("approved").asInt()).as(name + " approved").isEqualTo(approved);
		assertThat(entry.get("pending").asInt()).as(name + " pending").isEqualTo(pending);
		assertThat(entry.get("refused").asInt()).as(name + " refused").isEqualTo(refused);
		assertThat(entry.get("marketing").asInt()).as(name + " marketing").isEqualTo(marketing);
		assertThat(entry.get("formattingRefusal").asBoolean()).as(name + " formattingRefusal").isEqualTo(formatting);
	}

	private static Set<String> fieldNames(JsonNode node) {
		Set<String> names = new HashSet<>();
		node.fieldNames().forEachRemaining(names::add);
		return names;
	}
}
