package org.iskcon.kms.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.Permission;
import org.iskcon.kms.auth.RolePermissions;
import org.iskcon.kms.notification.NotificationDispatcher;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.testsupport.StubTokenVerifier;
import org.iskcon.kms.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Staff withdraw their own leave before it begins, the row is kept, and the right people are told
 * (T-184).
 *
 * <p>Rajeev's rulings of 2026-09-13, which each test below holds the code to: leave already under way
 * <em>"Cant be modified"</em>; the manager is told, <em>"YES"</em>; and the person is confirmed by
 * <em>"Email and WattsApp both. If both are setup IF not, Just email."</em>
 *
 * <p><strong>Same context as {@link StaffLeaveIT}, on purpose.</strong> No stub configuration of its
 * own, the same single {@code @MockBean} and no properties of its own, so Spring's context cache hands this class
 * the context StaffLeaveIT already built rather than a new one: CI has died of heap from contexts before.
 * The cost is that this context configures no mail relay, so an email attempt here records FAILED
 * ("no email sender is configured"). The channel tests therefore assert which channels were attempted,
 * which is the whole of the claim, and not whether a relay accepted the message.
 *
 * <p>Dates are relative to the temple's own today, since that is what the rule reads. The one test
 * about the day boundary moves the temple to a zone where it is a few minutes before or after midnight.
 */
@AutoConfigureMockMvc
class LeaveWithdrawalIT extends AbstractIntegrationTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	/** The zone the tenant row below is created in. */
	private static final ZoneId TEMPLE = ZoneId.of("Asia/Kolkata");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private NotificationDispatcher dispatcher;

	@Autowired
	private LeaveService leaveService;

	@MockBean
	private Scheduler scheduler; // no-op enqueue: a notice is recorded, and dispatched by hand where a test needs it

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID templeAdmin;
	private UUID cook;
	private UUID otherCook;
	private UUID manager;
	private UUID secondManager;
	private LocalDate today;

	@BeforeEach
	void setUp() throws Exception {
		admin = new JdbcTemplate(adminDataSource());
		today = LocalDate.now(TEMPLE);
		tenant = temple("radha-govinda", "Bengaluru Temple");
		templeAdmin = user(tenant, "uid-admin", "Temple Admin", "+919876500001", "TEMPLE_ADMIN");
		cook = user(tenant, "uid-cook", "Head Cook A", "+919876500081", "KITCHEN_STAFF");
		otherCook = user(tenant, "uid-cook-b", "Prep B", "+919876500082", "KITCHEN_STAFF");
		manager = user(tenant, "uid-manager", "Kitchen Manager", "+919876500083", "KITCHEN_MANAGER");
		secondManager = user(tenant, "uid-manager-2", "Second Manager", "+919876500084", "KITCHEN_MANAGER");
		signIn("uid-admin");
		hire(cook, "KITCHEN_STAFF");
		hire(otherCook, "KITCHEN_STAFF");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM platform_audit_events");
		admin.execute("DELETE FROM staff_leave");
		admin.execute("DELETE FROM staff_schedule_exceptions");
		admin.execute("DELETE FROM staff_schedule_template");
		admin.execute("DELETE FROM staff_profiles");
		// A hire puts the person in the temple's planner kitchen, seeding one where there is none (V150,
		// T-350); it holds its temple and creator, so it goes after the staff and before the users.
		admin.execute("DELETE FROM kitchens");
		admin.execute("DELETE FROM notification_attempts");
		admin.execute("DELETE FROM notifications");
		admin.execute("DELETE FROM tenant_settings");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ---- 1. a pending request ----------------------------------------------------------------------

	@Test
	@DisplayName("a pending request withdrawn before day one is kept as WITHDRAWN, audited, and every approver but the withdrawer is told")
	void pendingIsKeptAndEveryApproverIsTold() throws Exception {
		admin.update("UPDATE users SET status = 'DISABLED' WHERE id = ?", secondManager);

		signIn("uid-cook");
		UUID leave = ask(today.plusDays(3), today.plusDays(4));
		mvc.perform(authed(get("/api/v1/leave/mine")))
				.andExpect(jsonPath("$[0].canWithdraw").value(true));

		// The approver sees the same row, and it is not theirs to withdraw.
		signIn("uid-manager");
		mvc.perform(authed(get("/api/v1/leave")))
				.andExpect(jsonPath("$[0].id").value(leave.toString()))
				.andExpect(jsonPath("$[0].canWithdraw").value(false));

		signIn("uid-cook");
		withdraw(leave).andExpect(status().isNoContent());

		Map<String, Object> row = admin.queryForMap(
				"SELECT status, withdrawn_at, decided_at, decided_by FROM staff_leave WHERE id = ?", leave);
		assertThat(row.get("status")).isEqualTo("WITHDRAWN");
		assertThat(row.get("withdrawn_at")).isNotNull();
		assertThat(row.get("decided_at")).as("a withdrawal is not a decision").isNull();
		assertThat(row.get("decided_by")).isNull();
		mvc.perform(authed(get("/api/v1/leave/mine")))
				.andExpect(jsonPath("$[0].status").value("WITHDRAWN"))
				.andExpect(jsonPath("$[0].canWithdraw").value(false));

		Map<String, Object> audit = admin.queryForMap("""
				SELECT before_state->>'status' AS before, after_state->>'status' AS after, actor_user_id
				FROM audit_events WHERE entity_id = ? AND action = 'LEAVE_WITHDRAWN'
				""", leave);
		assertThat(audit.get("before")).isEqualTo("PENDING");
		assertThat(audit.get("after")).isEqualTo("WITHDRAWN");
		assertThat(audit.get("actor_user_id")).isEqualTo(cook);

		// Both holders of APPROVE_LEAVE who are active, and nobody else: not the disabled manager, not a cook.
		assertThat(noticeRecipients()).containsExactlyInAnyOrder(templeAdmin, manager);
		assertThat(noticeParam(manager, "state")).isEqualTo("still waiting for an answer");
		assertThat(noticeParam(manager, "name")).isEqualTo("Hired Person");
		assertThat(noticeParam(manager, "temple")).isEqualTo("Bengaluru Temple");
	}

	@Test
	@DisplayName("a withdrawer who can approve leave themselves is confirmed, and never sent the managers' notice")
	void theWithdrawerIsNotToldAsAManager() throws Exception {
		signIn("uid-admin");
		hire(manager, "KITCHEN_MANAGER");

		signIn("uid-manager");
		UUID leave = ask(today.plusDays(5), today.plusDays(5));
		withdraw(leave).andExpect(status().isNoContent());

		assertThat(noticeRecipients()).containsExactlyInAnyOrder(templeAdmin, secondManager);
		assertThat(count("LEAVE_WITHDRAWN", manager)).as("their own confirmation").isEqualTo(1);
	}

	@Test
	@DisplayName("approvers are found at this temple only, never through the withdrawer's own account at another")
	void approversAreThisTemplesOnly() throws Exception {
		// The row policy on users lets a signed-in person read their own rows at every temple, and the
		// withdrawal runs as them. Here the cook manages the kitchen at a second temple.
		UUID otherTemple = temple("gaura-nitai", "Mysuru Temple");
		UUID cookElsewhere = user(otherTemple, "uid-cook", "Head Cook A", "+919876500081", "KITCHEN_MANAGER");

		signIn("uid-cook");
		UUID leave = ask(today.plusDays(2), today.plusDays(2));
		withdraw(leave).andExpect(status().isNoContent());

		assertThat(noticeRecipients()).containsExactlyInAnyOrder(templeAdmin, manager, secondManager)
				.doesNotContain(cookElsewhere);
	}

	// ---- 2. approved leave -------------------------------------------------------------------------

	@Test
	@DisplayName("approved leave withdrawn before day one tells the person who approved it, and only them")
	void approvedTellsTheApprover() throws Exception {
		signIn("uid-cook");
		UUID leave = ask(today.plusDays(3), today.plusDays(3));
		answer("uid-manager", leave, "approve");

		signIn("uid-cook");
		withdraw(leave).andExpect(status().isNoContent());

		Map<String, Object> row = admin.queryForMap(
				"SELECT status, decided_by, decided_at, withdrawn_at FROM staff_leave WHERE id = ?", leave);
		assertThat(row.get("status")).isEqualTo("WITHDRAWN");
		assertThat(row.get("decided_by")).as("who approved it is kept").isEqualTo(manager);
		assertThat(row.get("decided_at")).isNotNull();
		assertThat(row.get("withdrawn_at")).isNotNull();
		assertThat(admin.queryForObject(
				"SELECT before_state->>'status' FROM audit_events WHERE entity_id = ? AND action = 'LEAVE_WITHDRAWN'",
				String.class, leave)).isEqualTo("APPROVED");

		assertThat(noticeRecipients()).containsExactly(manager);
		assertThat(noticeParam(manager, "state")).isEqualTo("approved");
	}

	@Test
	@DisplayName("when the approver's role here no longer carries APPROVE_LEAVE, every approver is told instead")
	void approverWhoLostThePermission() throws Exception {
		signIn("uid-cook");
		UUID leave = ask(today.plusDays(3), today.plusDays(3));
		answer("uid-manager", leave, "approve");
		admin.update("UPDATE users SET role = 'KITCHEN_STAFF' WHERE id = ?", manager);

		signIn("uid-cook");
		withdraw(leave).andExpect(status().isNoContent());

		assertThat(noticeRecipients()).containsExactlyInAnyOrder(templeAdmin, secondManager);
	}

	@Test
	@DisplayName("when the approver is disabled here, every approver is told instead")
	void approverWhoIsDisabled() throws Exception {
		signIn("uid-cook");
		UUID leave = ask(today.plusDays(3), today.plusDays(3));
		answer("uid-manager", leave, "approve");
		admin.update("UPDATE users SET status = 'DISABLED' WHERE id = ?", manager);

		signIn("uid-cook");
		withdraw(leave).andExpect(status().isNoContent());

		assertThat(noticeRecipients()).containsExactlyInAnyOrder(templeAdmin, secondManager);
	}

	@Test
	@DisplayName("leave the temple recorded on the person's behalf is theirs to withdraw, and whoever recorded it is told")
	void recordedOnBehalf() throws Exception {
		UUID profile = admin.queryForObject("SELECT id FROM staff_profiles WHERE user_id = ?", UUID.class, cook);
		signIn("uid-admin");
		String created = mvc.perform(authed(post("/api/v1/leave"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"staffProfileId":"%s","leaveType":"TIME_OFF","fromDate":"%s","toDate":"%s","halfDay":false}
								""".formatted(profile, today.plusDays(4), today.plusDays(4))))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		UUID leave = UUID.fromString(JSON.readTree(created).get("id").asText());

		// Nobody else's, though: a colleague is refused as before.
		signIn("uid-cook-b");
		withdraw(leave).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("KMS-400026"));

		signIn("uid-cook");
		withdraw(leave).andExpect(status().isNoContent());
		assertThat(noticeRecipients()).containsExactly(templeAdmin);
	}

	// ---- 3. refusals -------------------------------------------------------------------------------

	@Test
	@DisplayName("leave whose first day is today, or already past, is refused with KMS-400151 and left as it was")
	void startedLeaveIsRefused() throws Exception {
		signIn("uid-cook");
		UUID startsToday = ask(today, today.plusDays(1));
		UUID startedYesterday = ask(today.minusDays(3), today.minusDays(1));
		answer("uid-manager", startsToday, "approve");

		signIn("uid-cook");
		withdraw(startsToday).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("KMS-400151"));
		withdraw(startedYesterday).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("KMS-400151"));

		assertThat(statusOf(startsToday)).isEqualTo("APPROVED");
		assertThat(statusOf(startedYesterday)).isEqualTo("PENDING");
		assertThat(admin.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'LEAVE_WITHDRAWN'", Integer.class))
				.isZero();
		assertThat(noticeRecipients()).isEmpty();
		mvc.perform(authed(get("/api/v1/leave/mine")))
				.andExpect(jsonPath("$[*].canWithdraw").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(false))));
	}

	@Test
	@DisplayName("somebody else's leave is refused with KMS-400026, and a closed one with KMS-400090")
	void notYoursOrAlreadyClosed() throws Exception {
		signIn("uid-cook");
		UUID leave = ask(today.plusDays(3), today.plusDays(3));

		signIn("uid-cook-b");
		withdraw(leave).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("KMS-400026"));
		assertThat(statusOf(leave)).isEqualTo("PENDING");

		answer("uid-manager", leave, "decline");
		signIn("uid-cook");
		withdraw(leave).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("KMS-400090"));
		assertThat(statusOf(leave)).isEqualTo("DECLINED");

		// And a withdrawn row cannot be withdrawn, or answered, a second time.
		UUID again = ask(today.plusDays(6), today.plusDays(6));
		withdraw(again).andExpect(status().isNoContent());
		withdraw(again).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("KMS-400090"));
		signIn("uid-manager");
		mvc.perform(authed(post("/api/v1/leave/{id}/approve", again))
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400090"));
		assertThat(statusOf(again)).isEqualTo("WITHDRAWN");
	}

	// ---- 4. what a withdrawn row no longer does ----------------------------------------------------

	@Test
	@DisplayName("withdrawn leave comes off the rota, out of the approver's count, and does not block asking again")
	void aWithdrawnRowStopsCounting() throws Exception {
		UUID profile = admin.queryForObject("SELECT id FROM staff_profiles WHERE user_id = ?", UUID.class, cook);
		weekdayTemplate(profile);
		LocalDate wednesday = today.plusDays(2);
		while (wednesday.getDayOfWeek() != DayOfWeek.WEDNESDAY) {
			wednesday = wednesday.plusDays(1);
		}
		String weekStart = wednesday.minusDays(2).toString();

		signIn("uid-cook");
		UUID approved = ask(wednesday, wednesday);
		answer("uid-admin", approved, "approve");
		mvc.perform(authed(get("/api/v1/staff/schedule/week").param("weekStart", weekStart)))
				.andExpect(jsonPath("$.staff[?(@.staffProfileId == '%s')].days[2].working".formatted(profile)).value(false));

		signIn("uid-cook");
		withdraw(approved).andExpect(status().isNoContent());

		signIn("uid-admin");
		mvc.perform(authed(get("/api/v1/staff/schedule/week").param("weekStart", weekStart)))
				.andExpect(jsonPath("$.staff[?(@.staffProfileId == '%s')].days[2].working".formatted(profile)).value(true))
				.andExpect(jsonPath("$.staff[?(@.staffProfileId == '%s')].days[2].leaveId".formatted(profile)).value(
						org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())));

		// A pending request withdrawn leaves nothing waiting for an answer.
		signIn("uid-cook");
		UUID pending = ask(wednesday.plusDays(7), wednesday.plusDays(7));
		assertThat(awaiting()).isEqualTo(1);
		withdraw(pending).andExpect(status().isNoContent());
		assertThat(awaiting()).isZero();

		// The same days can be asked for again, which the overlap check refused while either was live.
		ask(wednesday, wednesday);
		ask(wednesday.plusDays(7), wednesday.plusDays(7));
	}

	// ---- 5. the person's confirmation --------------------------------------------------------------

	@Test
	@DisplayName("a temple without WhatsApp confirms by email alone, and the send tries email and nothing after it")
	void withoutWhatsAppEmailOnly() throws Exception {
		// Their own choice is SMS, and the ruling sends no SMS: this is the case where the two disagree.
		admin.update("UPDATE users SET preferred_channel = 'SMS' WHERE id = ?", cook);

		signIn("uid-cook");
		UUID leave = ask(today.plusDays(3), today.plusDays(3));
		withdraw(leave).andExpect(status().isNoContent());

		List<Map<String, Object>> confirmations = confirmationsTo(cook);
		assertThat(confirmations).extracting(n -> n.get("preferred_channel")).containsExactly("EMAIL");
		assertThat(confirmations).extracting(n -> n.get("status")).containsExactly("PENDING");

		UUID email = (UUID) confirmations.get(0).get("id");
		dispatch(email);
		assertThat(attemptedChannels(email)).containsExactly("EMAIL");
		assertThat(smsAttemptsFor(cook)).isZero();
	}

	@Test
	@DisplayName("a temple with WhatsApp confirms on WhatsApp and by email, one of each, and a failed WhatsApp sends nothing more")
	void withWhatsAppBothAndNeitherFallsBack() throws Exception {
		admin.update("UPDATE users SET preferred_channel = 'SMS' WHERE id = ?", cook);
		// Connected as the settings screen reads it. No token is stored, so the WhatsApp adapter refuses
		// before any call to Meta: the send fails on WhatsApp, which is exactly the case under test.
		// V55's shape check: a phone number id comes with its account id and callback token.
		admin.update("""
				INSERT INTO tenant_settings (tenant_id, whatsapp_phone_number_id, whatsapp_waba_id, whatsapp_webhook_token)
				VALUES (?, 'pn-t184', 'waba-t184', 'wh-token-t184')
				ON CONFLICT (tenant_id) DO UPDATE SET whatsapp_phone_number_id = EXCLUDED.whatsapp_phone_number_id,
					whatsapp_waba_id = EXCLUDED.whatsapp_waba_id, whatsapp_webhook_token = EXCLUDED.whatsapp_webhook_token
				""", tenant);

		signIn("uid-cook");
		UUID leave = ask(today.plusDays(3), today.plusDays(3));
		answer("uid-manager", leave, "approve");
		signIn("uid-cook");
		withdraw(leave).andExpect(status().isNoContent());

		List<Map<String, Object>> confirmations = confirmationsTo(cook);
		assertThat(confirmations).extracting(n -> n.get("preferred_channel"))
				.containsExactlyInAnyOrder("WHATSAPP", "EMAIL");

		for (Map<String, Object> n : confirmations) {
			dispatch((UUID) n.get("id"));
		}
		UUID whatsapp = idOn(confirmations, "WHATSAPP");
		UUID email = idOn(confirmations, "EMAIL");
		assertThat(attemptedChannels(whatsapp)).as("WhatsApp failed and went nowhere else").containsExactly("WHATSAPP");
		assertThat(admin.queryForObject("SELECT status FROM notifications WHERE id = ?", String.class, whatsapp))
				.isEqualTo("FAILED");
		assertThat(attemptedChannels(email)).containsExactly("EMAIL");
		assertThat(smsAttemptsFor(cook)).isZero();
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM notification_attempts a JOIN notifications n ON n.id = a.notification_id "
						+ "WHERE n.recipient_user_id = ? AND a.channel = 'EMAIL'", Integer.class, cook))
				.as("one email attempt for the person, not two").isEqualTo(1);

		// And the option is narrow: the manager's notice, the ordinary kind, still cascades.
		UUID notice = admin.queryForObject(
				"SELECT id FROM notifications WHERE template = 'LEAVE_WITHDRAWN_NOTICE' AND recipient_user_id = ?",
				UUID.class, manager);
		dispatch(notice);
		// In any order: the three attempts are written in one transaction, so they share created_at and
		// the table holds nothing else to order them by. That it reached all three is the claim.
		assertThat(attemptedChannels(notice)).containsExactlyInAnyOrder("WHATSAPP", "SMS", "EMAIL");
	}

	// ---- 6. the day boundary, in the temple's zone -------------------------------------------------

	@Test
	@DisplayName("canWithdraw and the refusal both turn over at the temple's midnight, not the server's")
	void theBoundaryIsTheTemplesMidnight() throws Exception {
		signIn("uid-cook");
		ZoneOffset[] zones = eitherSideOfMidnight();
		ZoneOffset beforeMidnight = zones[0];
		ZoneOffset afterMidnight = zones[1];
		Instant now = Instant.now();
		LocalDate firstDay = now.atZone(afterMidnight).toLocalDate();
		assertThat(now.atZone(beforeMidnight).toLocalDate().plusDays(1)).isEqualTo(firstDay);

		UUID leave = ask(firstDay, firstDay);

		// At 23:xx the leave starts tomorrow.
		templeZone(beforeMidnight);
		mvc.perform(authed(get("/api/v1/leave/mine"))).andExpect(jsonPath("$[0].canWithdraw").value(true));

		// At 00:xx, an hour east, it starts today.
		templeZone(afterMidnight);
		mvc.perform(authed(get("/api/v1/leave/mine"))).andExpect(jsonPath("$[0].canWithdraw").value(false));
		withdraw(leave).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("KMS-400151"));
		assertThat(statusOf(leave)).isEqualTo("PENDING");

		templeZone(beforeMidnight);
		withdraw(leave).andExpect(status().isNoContent());
		assertThat(statusOf(leave)).isEqualTo("WITHDRAWN");
	}

	@Test
	@DisplayName("the rule itself: the first day counts as begun, the day after today does not")
	void theRuleOnDates() {
		assertThat(LeaveService.notYetBegun(today.plusDays(1), today)).isTrue();
		assertThat(LeaveService.notYetBegun(today, today)).isFalse();
		assertThat(LeaveService.notYetBegun(today.minusDays(1), today)).isFalse();
		assertThat(LeaveService.stillOpen(LeaveStatus.PENDING)).isTrue();
		assertThat(LeaveService.stillOpen(LeaveStatus.APPROVED)).isTrue();
		assertThat(Arrays.stream(LeaveStatus.values()).filter(LeaveService::stillOpen))
				.containsExactly(LeaveStatus.PENDING, LeaveStatus.APPROVED);
	}

	@Test
	@DisplayName("the roles told as approvers are exactly the roles RolePermissions gives APPROVE_LEAVE")
	void approverRolesFollowThePolicy() {
		for (User.Role role : User.Role.values()) {
			assertThat(LeaveService.rolesThatApproveLeave().contains(role))
					.as("%s", role)
					.isEqualTo(RolePermissions.has(role, Permission.APPROVE_LEAVE));
		}
		// Today's policy, stated so a change to it is seen here as well as honoured.
		assertThat(LeaveService.rolesThatApproveLeave())
				.containsExactlyInAnyOrder(User.Role.TEMPLE_ADMIN, User.Role.KITCHEN_MANAGER);
	}

	// ---------------------------------------------------------------------

	/**
	 * Two whole-hour offsets an hour apart, the first at 23:xx now and the second at 00:xx. One always
	 * exists with the first no further east than +13, since offsets from -12 to +13 cover every hour.
	 * A run in the last quarter-minute of a UTC hour waits for the next, so the pair cannot turn over
	 * during the few requests that use it.
	 */
	private static ZoneOffset[] eitherSideOfMidnight() throws InterruptedException {
		ZonedDateTime utc = Instant.now().atZone(ZoneOffset.UTC);
		if (utc.getMinute() == 59 && utc.getSecond() >= 45) {
			Thread.sleep((61 - utc.getSecond()) * 1000L);
		}
		Instant now = Instant.now();
		for (int hours = -12; hours <= 13; hours++) {
			if (now.atZone(ZoneOffset.ofHours(hours)).getHour() == 23) {
				return new ZoneOffset[] {ZoneOffset.ofHours(hours), ZoneOffset.ofHours(hours + 1)};
			}
		}
		throw new IllegalStateException("no whole-hour offset is at 23:00 now, which cannot happen");
	}

	private void templeZone(ZoneOffset zone) {
		admin.update("UPDATE tenants SET timezone = ? WHERE id = ?", zone.getId(), tenant);
	}

	private int awaiting() {
		TenantContext.set(tenant);
		try {
			return leaveService.awaitingDecision(today.plusDays(1)).total();
		} finally {
			TenantContext.clear();
		}
	}

	private void dispatch(UUID notificationId) {
		TenantContext.set(tenant);
		try {
			dispatcher.dispatch(notificationId);
		} finally {
			TenantContext.clear();
		}
	}

	private List<String> attemptedChannels(UUID notificationId) {
		return admin.queryForList(
				"SELECT channel FROM notification_attempts WHERE notification_id = ? ORDER BY created_at, id",
				String.class, notificationId);
	}

	private int smsAttemptsFor(UUID userId) {
		return admin.queryForObject(
				"SELECT count(*) FROM notification_attempts a JOIN notifications n ON n.id = a.notification_id "
						+ "WHERE n.recipient_user_id = ? AND a.channel = 'SMS'", Integer.class, userId);
	}

	private List<Map<String, Object>> confirmationsTo(UUID userId) {
		return admin.queryForList(
				"SELECT id, preferred_channel, status FROM notifications WHERE template = 'LEAVE_WITHDRAWN' AND recipient_user_id = ?",
				userId);
	}

	private static UUID idOn(List<Map<String, Object>> notifications, String channel) {
		return notifications.stream().filter(n -> channel.equals(n.get("preferred_channel")))
				.map(n -> (UUID) n.get("id")).findFirst().orElseThrow();
	}

	private List<UUID> noticeRecipients() {
		return admin.queryForList(
				"SELECT recipient_user_id FROM notifications WHERE template = 'LEAVE_WITHDRAWN_NOTICE'", UUID.class);
	}

	private String noticeParam(UUID recipient, String key) {
		return admin.queryForObject(
				"SELECT params->>? FROM notifications WHERE template = 'LEAVE_WITHDRAWN_NOTICE' AND recipient_user_id = ?",
				String.class, key, recipient);
	}

	private int count(String template, UUID recipient) {
		return admin.queryForObject(
				"SELECT count(*) FROM notifications WHERE template = ? AND recipient_user_id = ?",
				Integer.class, template, recipient);
	}

	private String statusOf(UUID leave) {
		return admin.queryForObject("SELECT status FROM staff_leave WHERE id = ?", String.class, leave);
	}

	private void answer(String approverUid, UUID leave, String verb) throws Exception {
		signIn(approverUid);
		mvc.perform(authed(post("/api/v1/leave/{id}/" + verb, leave))
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isNoContent());
	}

	private org.springframework.test.web.servlet.ResultActions withdraw(UUID leave) throws Exception {
		return mvc.perform(authed(delete("/api/v1/leave/mine/{id}", leave)));
	}

	private UUID ask(LocalDate from, LocalDate to) throws Exception {
		String created = mvc.perform(authed(post("/api/v1/leave/mine"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"leaveType\":\"TIME_OFF\",\"fromDate\":\"%s\",\"toDate\":\"%s\",\"halfDay\":false}"
								.formatted(from, to)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return UUID.fromString(JSON.readTree(created).get("id").asText());
	}

	/** Puts somebody with a login on the register, as the temple admin. */
	private UUID hire(UUID userId, String systemAccess) throws Exception {
		String created = mvc.perform(authed(post("/api/v1/staff/members"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"existingUserId\":\"" + userId + "\",\"fullName\":\"Hired Person\","
								+ "\"jobTitle\":\"COOK\",\"employmentType\":\"FULL_TIME\","
								+ "\"dateOfJoining\":\"2026-01-05\",\"systemAccess\":\"" + systemAccess + "\","
								// Every staff member belongs to a kitchen (Epic 12, KMS-400184 without one).
								+ "\"kitchenId\":\"" + org.iskcon.kms.meal.MealFixture.plannerKitchen(admin, tenant, null) + "\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return UUID.fromString(JSON.readTree(created).get("id").asText());
	}

	/** Monday to Friday, nine to five. */
	private void weekdayTemplate(UUID profile) throws Exception {
		StringBuilder days = new StringBuilder();
		for (int d = 1; d <= 7; d++) {
			if (days.length() > 0) {
				days.append(",");
			}
			days.append(d <= 5
					? "{\"dayOfWeek\":" + d + ",\"working\":true,\"startTime\":\"09:00\",\"endTime\":\"17:00\"}"
					: "{\"dayOfWeek\":" + d + ",\"working\":false}");
		}
		signIn("uid-admin");
		mvc.perform(authed(put("/api/v1/staff/profiles/{id}/template", profile))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"days\":[" + days + "]}"))
				.andExpect(status().isNoContent());
	}

	private UUID temple(String slug, String name) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class, slug, name);
	}

	/** Consented, so a notice is queued rather than suppressed. Fake numbers only. */
	private UUID user(UUID tenantId, String uid, String name, String phone, String role) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status, contact_consent_at)
				VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', now()) RETURNING id
				""", UUID.class, tenantId, uid, name, uid + "@example.com", phone, role);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}
}
