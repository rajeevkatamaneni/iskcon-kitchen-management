package org.iskcon.kms.shift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.Permission;
import org.iskcon.kms.auth.RolePermissions;
import org.iskcon.kms.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * "Taken off in the last week" on My Shifts (T-149): the volunteer a coordinator removed, or whose
 * shift was cancelled with them on it, can see that it happened even when the notice never arrived.
 *
 * <p><strong>Every absence here is asserted beside a presence in the same response.</strong> "The
 * volunteer's own release is not listed", "another volunteer's removal is not listed" and "older
 * than a week is not listed" would all pass against an endpoint that returns nothing at all, so each
 * of those tests also sets up one row that <em>must</em> appear and pins the exact count. The note's
 * absence is pinned as the exact JSON field set of a real row rather than as a missing path, for the
 * same reason: a check for one field not being there cannot see a field that has been renamed.
 *
 * <p>The removal and the cancellation go through the coordinator's real endpoints rather than being
 * written in with fixtures, so the rows this reads are the rows the application actually stores.
 * Only the clock is moved by hand, in the week-boundary test, because a week cannot be waited out.
 *
 * <p>Borrows {@link ReleaseIT}'s verifier configuration and the same {@code @MockBean} rather than
 * declaring its own copies: both are part of Spring's context cache key, so this class then shares
 * ReleaseIT's application context instead of starting another one.
 */
@AutoConfigureMockMvc
@Import(ReleaseIT.StubVerifierConfiguration.class)
class MyReleasedShiftsIT extends AbstractIntegrationTest {

	/** Far enough ahead that no shift here has started, which removal and release both require. */
	private static final String FUTURE = "2030-12-01";

	private static final String NOTE = "Kept arriving an hour late - internal, never to be shown";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper json;

	@Autowired
	private ReleaseIT.StubTokenVerifier stubVerifier;

	@MockBean
	private Scheduler scheduler;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID staffId;
	private UUID vol1;
	private UUID vol2;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		staffId = user(tenant, "uid-staff", "Staff", "+919876500001", "KITCHEN_STAFF");
		vol1 = user(tenant, "uid-vol-1", "Vol One", "+919876500091", "VOLUNTEER");
		vol2 = user(tenant, "uid-vol-2", "Vol Two", "+919876500092", "VOLUNTEER");
		admin.update("UPDATE users SET contact_consent_at = now() WHERE role = 'VOLUNTEER'");
	}

	@AfterEach
	void tearDown() {
		// First, because audit_events.actor_user_id is ON DELETE RESTRICT and a coordinator's
		// removal files one (T-080).
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM shift_waitlist");
		admin.execute("DELETE FROM shift_signups");
		admin.execute("DELETE FROM shifts");
		admin.execute("DELETE FROM notification_attempts");
		admin.execute("DELETE FROM notifications");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("a coordinator's removal is listed with its reason, and the internal note is not in the response")
	void removalIsListedWithoutTheNote() throws Exception {
		UUID shift = shift("Sunday prep", "08:00", "12:00");
		signup(shift, vol1);
		remove(shift, vol1, "ROTA_CHANGED");

		signIn("uid-vol-1");
		String body = mvc.perform(authed(get("/api/v1/my-shifts/released")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].shiftId").value(shift.toString()))
				.andExpect(jsonPath("$[0].title").value("Sunday prep"))
				.andExpect(jsonPath("$[0].shiftDate").value(FUTURE))
				.andExpect(jsonPath("$[0].reason").value("ROTA_CHANGED"))
				.andReturn().getResponse().getContentAsString();

		// The exact field set, which is the wire contract with MyReleasedShiftView in api.ts. Pinned
		// whole rather than as "no releasedNote", because a note leaking under any other name would
		// sail past a check for that one name.
		JsonNode row = json.readTree(body).get(0);
		Set<String> fields = new HashSet<>();
		row.fieldNames().forEachRemaining(fields::add);
		assertThat(fields).containsExactlyInAnyOrder(
				"signupId", "shiftId", "title", "shiftDate", "startTime", "endTime", "location",
				"releasedAt", "reason");
		// And the note's words, anywhere in the body, under any key.
		assertThat(body).doesNotContain("internal, never to be shown");

		// releasedAt is the removal, read back from the row.
		OffsetDateTime stored = admin.queryForObject(
				"SELECT released_at FROM shift_signups WHERE shift_id = ? AND volunteer_user_id = ?",
				OffsetDateTime.class, shift, vol1);
		assertThat(Instant.parse(row.get("releasedAt").asText())).isEqualTo(stored.toInstant());
	}

	@Test
	@DisplayName("the volunteer's own release is not listed, beside a removal that is")
	void ownReleaseIsNotListed() throws Exception {
		UUID stepped = shift("Stepped off myself", "08:00", "12:00");
		UUID removed = shift("Taken off by the temple", "14:00", "18:00");
		signup(stepped, vol1);
		signup(removed, vol1);

		signIn("uid-vol-1");
		mvc.perform(authed(post("/api/v1/shifts/{id}/release", stepped))).andExpect(status().isNoContent());
		remove(removed, vol1, "NO_LONGER_NEEDED");

		signIn("uid-vol-1");
		mvc.perform(authed(get("/api/v1/my-shifts/released")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].title").value("Taken off by the temple"))
				.andExpect(jsonPath("$[0].reason").value("NO_LONGER_NEEDED"));
	}

	@Test
	@DisplayName("a shift cancelled with the volunteer still on it is listed as SHIFT_CANCELLED, at the cancellation")
	void cancelledShiftIsListed() throws Exception {
		UUID shift = shift("Festival cooking", "20:00", "02:00");
		signup(shift, vol1);

		signIn("uid-staff");
		mvc.perform(authed(post("/api/v1/shifts/{id}/cancel", shift))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"Rain\"}"))
				.andExpect(status().isNoContent());

		signIn("uid-vol-1");
		String body = mvc.perform(authed(get("/api/v1/my-shifts/released")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].title").value("Festival cooking"))
				.andExpect(jsonPath("$[0].reason").value("SHIFT_CANCELLED"))
				.andReturn().getResponse().getContentAsString();

		OffsetDateTime cancelledAt = admin.queryForObject(
				"SELECT cancelled_at FROM shifts WHERE id = ?", OffsetDateTime.class, shift);
		assertThat(Instant.parse(json.readTree(body).get(0).get("releasedAt").asText()))
				.isEqualTo(cancelledAt.toInstant());
	}

	@Test
	@DisplayName("nothing that came off more than seven days ago is listed, by removal or by cancellation")
	void olderThanAWeekIsNotListed() throws Exception {
		UUID oldRemoval = shift("Removed eight days ago", "06:00", "08:00");
		UUID oldCancel = shift("Cancelled eight days ago", "09:00", "11:00");
		UUID recent = shift("Removed six days ago", "12:00", "14:00");
		signup(oldRemoval, vol1);
		signup(oldCancel, vol1);
		signup(recent, vol1);
		remove(oldRemoval, vol1, "OTHER");
		remove(recent, vol1, "OTHER");
		signIn("uid-staff");
		mvc.perform(authed(post("/api/v1/shifts/{id}/cancel", oldCancel))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"Rain\"}"))
				.andExpect(status().isNoContent());

		// A week cannot be waited out, so the moments are moved back. Eight days either side of the
		// window, and six days inside it so the window is shown to have an inside.
		admin.update("UPDATE shift_signups SET released_at = now() - interval '8 days' WHERE shift_id = ?", oldRemoval);
		admin.update("UPDATE shifts SET cancelled_at = now() - interval '8 days' WHERE id = ?", oldCancel);
		admin.update("UPDATE shift_signups SET released_at = now() - interval '6 days' WHERE shift_id = ?", recent);

		signIn("uid-vol-1");
		mvc.perform(authed(get("/api/v1/my-shifts/released")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].title").value("Removed six days ago"));
	}

	@Test
	@DisplayName("another volunteer's removal is never listed, and each volunteer sees their own")
	void anotherVolunteersRemovalIsNotListed() throws Exception {
		UUID mine = shift("My shift", "08:00", "12:00");
		UUID theirs = shift("Their shift", "14:00", "18:00");
		signup(mine, vol1);
		signup(theirs, vol2);
		remove(mine, vol1, "ROTA_CHANGED");
		remove(theirs, vol2, "OTHER");

		signIn("uid-vol-1");
		mvc.perform(authed(get("/api/v1/my-shifts/released")))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].title").value("My shift"));

		signIn("uid-vol-2");
		mvc.perform(authed(get("/api/v1/my-shifts/released")))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].title").value("Their shift"));
	}

	@Test
	@DisplayName("every role without VIEW_OWN_SHIFTS is refused, and every role with it is let in")
	void refusedWithoutViewOwnShifts() throws Exception {
		// The roles are read from the policy rather than written down here, so this does not drift
		// when RolePermissions changes; it asserts only that the endpoint obeys whatever the policy
		// says. It must find at least one refused role, or its refusal half proves nothing.
		List<User.Role> refused = java.util.Arrays.stream(User.Role.values())
				.filter(r -> !RolePermissions.has(r, Permission.VIEW_OWN_SHIFTS)).toList();
		assertThat(refused).as("some role must lack VIEW_OWN_SHIFTS, or nothing here is refused").isNotEmpty();

		for (User.Role role : User.Role.values()) {
			String uid = "uid-role-" + role.name().toLowerCase();
			// The operator belongs to no temple (V1's users row with a NULL tenant), like every
			// SUPER_ADMIN fixture in this suite.
			UUID roleTenant = role == User.Role.SUPER_ADMIN ? null : tenant;
			String phone = "+91980000%04d".formatted(role.ordinal());
			// Consent given for every role, so that nothing but the permission can be what refuses.
			admin.update("""
					INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status, contact_consent_at)
					VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', now())
					""", roleTenant, uid, role.name(), uid + "@example.com", phone, role.name());

			signIn(uid);
			if (refused.contains(role)) {
				mvc.perform(authed(get("/api/v1/my-shifts/released")))
						.andExpect(status().isForbidden());
			} else {
				mvc.perform(authed(get("/api/v1/my-shifts/released")))
						.andExpect(status().isOk());
			}
		}
	}

	// ---------------------------------------------------------------------

	private UUID user(UUID tenantId, String uid, String name, String phone, String role) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')
				RETURNING id
				""", UUID.class, tenantId, uid, name, uid + "@example.com", phone, role);
	}

	private UUID shift(String title, String start, String end) {
		return admin.queryForObject("""
				INSERT INTO shifts (tenant_id, title, shift_date, start_time, end_time, capacity, created_by)
				VALUES (?, ?, ?::date, ?::time, ?::time, 3, ?) RETURNING id
				""", UUID.class, tenant, title, FUTURE, start, end, staffId);
	}

	private void signup(UUID shift, UUID volunteer) {
		admin.update("INSERT INTO shift_signups (tenant_id, shift_id, volunteer_user_id) VALUES (?, ?, ?)",
				tenant, shift, volunteer);
	}

	/** The coordinator's removal through its real endpoint, with a reason and the internal note. */
	private void remove(UUID shift, UUID volunteer, String reason) throws Exception {
		signIn("uid-staff");
		mvc.perform(authed(post("/api/v1/shifts/{id}/signups/{userId}/release", shift, volunteer))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"%s\",\"internalNote\":\"%s\"}".formatted(reason, NOTE)))
				.andExpect(status().isNoContent());
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}
}
