package org.iskcon.kms.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The Settings Test button sending a real WhatsApp message to a number the administrator types
 * (T-151).
 *
 * <p>Rajeev, 2026-09-12: <em>"Ask the use for a phone number to send a test message."</em> The
 * button used to ask Meta to describe the temple's number and send nothing. What this pins is the
 * difference: one real send to the number typed, and {@code whatsapp_last_sent_at} stamped only on
 * the far side of Meta handing back a message id — the column the purchase-order screen's Send on
 * WhatsApp button waits for (T-136).
 *
 * <p><strong>Meta is mocked at the client boundary and nothing leaves the building.</strong> The
 * service is built by hand around a Mockito {@link MetaWhatsAppClient} and the context's real
 * {@code JdbcTemplate}, secret store and audit service, and the controller is driven through a
 * standalone MockMvc carrying the application's real {@link GlobalExceptionHandler} and real bean
 * validation. That is deliberate, and it is about the suite's memory rather than convenience: a
 * {@code @MockBean} of the client, or an {@code @Import} of a stub token verifier, would give this
 * class an application context of its own, and the note on context caching in
 * {@code AbstractIntegrationTest} records what that has cost CI before. As it stands this class has no
 * {@code @MockBean}, no {@code @Import} and no {@code @TestPropertySource}, and shares the default
 * context.
 *
 * <p>What that standalone setup does not exercise is the security filter chain, so the permission is
 * pinned on the method itself instead; it is the same {@code MANAGE_TEMPLE_SETTINGS} the endpoint has
 * always had.
 *
 * <p>The database is real, because the stamp is read and written through {@code tenant_settings}' RLS
 * policy with the tenant taken from the connection, and mocking that would prove nothing. All phone
 * numbers here are fake.
 */
class WhatsAppTestSendIT extends AbstractIntegrationTest {

	private static final String TEST_NUMBER = "+919876500000";
	private static final OffsetDateTime LONG_AGO = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

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

	private JdbcTemplate admin;
	private MetaWhatsAppClient meta;
	private MockMvc mvc;
	private UUID govinda;

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
				VALUES (?, 'uid-admin-t151', 'Temple Admin', 'admin-t151@example.com', '+919876500090',
						'TEMPLE_ADMIN', 'ACTIVE')
				""", govinda);

		meta = mock(MetaWhatsAppClient.class);
		TenantWhatsAppSettingsService service = new TenantWhatsAppSettingsService(
				jdbc, secrets, auditService, meta, "https://kms.example");
		mvc = MockMvcBuilders.standaloneSetup(new WhatsAppSettingsController(service, emails))
				.setControllerAdvice(new GlobalExceptionHandler())
				.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
				.setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
				.build();

		TenantContext.set(govinda);
		AuthenticatedUser actor = new AuthenticatedUser(users.findByFirebaseUid("uid-admin-t151").orElseThrow());
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

	/** Connected, with a token stored, verified long ago and never having sent anything. */
	private void connect() {
		admin.update("""
				INSERT INTO tenant_settings (tenant_id, whatsapp_phone_number_id, whatsapp_waba_id,
						whatsapp_webhook_token, whatsapp_display_number, whatsapp_verified_at,
						whatsapp_templates_submitted_at)
				VALUES (?, 'phone-govinda', 'waba-govinda', 'tok-govinda', 'Temple Kitchen', ?, ?)
				""", govinda, LONG_AGO, LONG_AGO);
		secrets.put(govinda, TenantSecretStore.Kind.WHATSAPP_ACCESS_TOKEN, "token-govinda");
	}

	private ResultActions sendTestTo(String phoneNumber) throws Exception {
		return mvc.perform(post("/api/v1/settings/whatsapp/test")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("phoneNumber", phoneNumber))));
	}

	private Object lastSent() {
		return admin.queryForMap("SELECT whatsapp_last_sent_at FROM tenant_settings WHERE tenant_id = ?", govinda)
				.get("whatsapp_last_sent_at");
	}

	private OffsetDateTime verifiedAt() {
		return admin.queryForObject("SELECT whatsapp_verified_at FROM tenant_settings WHERE tenant_id = ?",
				OffsetDateTime.class, govinda);
	}

	private int testSendsAudited() {
		return admin.queryForObject("""
				SELECT count(*) FROM audit_events
				WHERE tenant_id = ? AND after_state ->> 'whatsappTestSentTo' IS NOT NULL
				""", Integer.class, govinda);
	}

	@Test
	@DisplayName("a test sends one real message to the number typed, and stamps the temple as able to send")
	void sendsOneRealMessageAndStamps() throws Exception {
		connect();
		when(meta.sendTemplate(anyString(), anyString(), anyString(), anyString(), anyString(), anyList()))
				.thenReturn("wamid.TEST151");

		sendTestTo(TEST_NUMBER)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.connected").value(true))
				.andExpect(jsonPath("$.phoneNumberId").value("phone-govinda"));

		// Exactly one send, from the temple's own number and token, of the test template, naming the
		// temple — and nothing else asked of Meta, so no credential check is hiding behind it.
		verify(meta, times(1)).sendTemplate("phone-govinda", "token-govinda", TEST_NUMBER,
				"connection_test", "en", List.of("Sri Sri Radha Govinda Temple"));
		verifyNoMoreInteractions(meta);

		assertThat(lastSent()).as("whatsapp_last_sent_at after Meta returned an id").isNotNull();
		// A send Meta accepted under this token proves the credentials, so "Last checked" moves.
		assertThat(verifiedAt()).isAfter(LONG_AGO);
		assertThat(testSendsAudited()).isEqualTo(1);
	}

	@Test
	@DisplayName("a malformed number is KMS-400003, and nothing is sent or stamped")
	void malformedNumberIsRefusedBeforeMeta() throws Exception {
		connect();

		sendTestTo("98765 00000")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400003"));

		verifyNoInteractions(meta);
		assertThat(lastSent()).isNull();
	}

	@Test
	@DisplayName("a blank number is asked for, not called malformed, and nothing is sent")
	void blankNumberIsNotCalledMalformed() throws Exception {
		connect();

		sendTestTo("  ")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.reference()));

		verifyNoInteractions(meta);
	}

	/**
	 * The refusal that matters most, because it happens with perfect credentials.
	 *
	 * <p>A number outside a test account's recipient list, a template still in Meta's review — the
	 * old Test button said yes to both. Meta's own sentence is specific and technical, and it goes to
	 * the log, never to the administrator.
	 */
	@Test
	@DisplayName("Meta refusing the message is KMS-500007, stamps nothing, and none of Meta's words reach the screen")
	void metaRefusalIsKms500007AndStampsNothing() throws Exception {
		connect();
		when(meta.sendTemplate(anyString(), anyString(), anyString(), anyString(), anyString(), anyList()))
				.thenThrow(new MetaWhatsAppClient.WhatsAppSendFailed(
						"(#131030) Recipient phone number not in allowed list"));

		String body = sendTestTo(TEST_NUMBER)
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.code").value("KMS-500007"))
				.andReturn().getResponse().getContentAsString();

		assertThat(body).doesNotContain("131030").doesNotContain("allowed list").doesNotContain("Recipient");
		assertThat(lastSent()).isNull();
		assertThat(verifiedAt()).isEqualTo(LONG_AGO);
		assertThat(testSendsAudited()).isZero();
	}

	@Test
	@DisplayName("Meta unreachable is the same KMS-500007, and stamps nothing")
	void metaUnreachableIsKms500007() throws Exception {
		connect();
		when(meta.sendTemplate(anyString(), anyString(), anyString(), anyString(), anyString(), anyList()))
				.thenThrow(new MetaWhatsAppClient.WhatsAppCredentialsRejected("Could not reach Meta just now."));

		String body = sendTestTo(TEST_NUMBER)
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.code").value("KMS-500007"))
				.andReturn().getResponse().getContentAsString();

		assertThat(body).doesNotContain("Could not reach Meta");
		assertThat(lastSent()).isNull();
	}

	@Test
	@DisplayName("a temple that has connected nothing is refused as before, and Meta is never asked")
	void notConnectedIsRefusedAsBefore() throws Exception {
		sendTestTo(TEST_NUMBER)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.reference()));

		verifyNoInteractions(meta);
	}

	@Test
	@DisplayName("the test send is behind MANAGE_TEMPLE_SETTINGS, as the check it replaced was")
	void permissionIsUnchanged() throws Exception {
		PreAuthorize rule = WhatsAppSettingsController.class
				.getMethod("test", SendWhatsAppTestRequest.class, AuthenticatedUser.class)
				.getAnnotation(PreAuthorize.class);

		assertThat(rule).isNotNull();
		assertThat(rule.value()).isEqualTo("hasAuthority('MANAGE_TEMPLE_SETTINGS')");
	}
}
