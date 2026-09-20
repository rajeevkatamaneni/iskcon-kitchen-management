package org.iskcon.kms.shift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.testsupport.StubTokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The temple's own arrangement of its left-hand menu (V154, T-420).
 *
 * <p>Rajeev approved this on 2026-09-19: a Temple Admin opens Settings → Menu, moves items and
 * groups about, renames a group, adds one, and can put the standard menu back. What they save is
 * what everybody at that temple sees.
 *
 * <p>There is not much of the feature on this side, and that is the design rather than an omission —
 * the destinations live in {@code frontend/lib/nav.ts} and the merge happens in the browser. So what
 * is worth asserting here is specific:
 *
 * <ul>
 *   <li><b>The arrangement belongs to the temple.</b> An administrator arranges, and the cook who
 *       cannot open Settings gets the same menu. It rides on {@code /whoami} for that reason, and as
 *       an object rather than as text, because the client's type says it is one.
 *   <li><b>Never having arranged is not the same as having arranged it to look standard</b>, so
 *       null means null and reset writes null back.
 *   <li><b>One temple's menu is not another's</b>, through the application's own unprivileged role
 *       rather than as a superuser, which would prove nothing about the policy.
 *   <li><b>The two refusals that carry a code</b> — an arrangement that cannot be read, and one that
 *       places the same destination twice — answer with the codes somebody may quote off a
 *       screenshot a year from now, so the codes are asserted and not only the statuses.
 *   <li><b>And the bounds that are not codes</b> come back as field errors, because a person can be
 *       shown the box.
 * </ul>
 *
 * <p>Nothing here asserts that an item id names a real page. That is deliberate and is the point of
 * V154 §1: this side holds no copy of the menu, so an unknown id is stored and passed over rather
 * than refused.
 */
@AutoConfigureMockMvc
class MenuLayoutSettingsIT extends AbstractIntegrationTest {

	/** Two groups, four destinations, in an order nothing would produce by accident. */
	private static final String ARRANGEMENT = """
			{"version":1,"groups":[
				{"id":"top","title":null,"items":["dashboard"]},
				{"id":"kitchen","title":"Kitchen","items":["meals","recipes","deliveries"]}]}
			""";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID templeA;
	private UUID templeB;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		templeA = insertTenant("radha-govinda", "Sri Sri Radha Govinda Temple");
		templeB = insertTenant("radha-krishna", "Sri Sri Radha Krishna Temple");
		insertUser(templeA, "uid-admin-a", "TEMPLE_ADMIN", "+919876500081");
		insertUser(templeA, "uid-manager-a", "KITCHEN_MANAGER", "+919876500082");
		insertUser(templeA, "uid-cook-a", "KITCHEN_STAFF", "+919876500083");
		insertUser(templeB, "uid-admin-b", "TEMPLE_ADMIN", "+919876500084");
		insertOperator("uid-operator");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM tenant_settings");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ------------------------------------------------------------------ saving and reading back

	@Test
	@DisplayName("what the admin arranges is what the cook's menu is given, as an object and in order")
	void theArrangementReachesEverybodyAsAnObject() throws Exception {
		signIn("uid-admin-a");
		save(ARRANGEMENT).andExpect(status().isNoContent());

		// The cook, who may not open Settings at all. This is the whole point of the feature and the
		// reason the arrangement rides on the session rather than on a settings endpoint.
		signIn("uid-cook-a");
		mvc.perform(authed(get("/api/v1/whoami")))
				.andExpect(status().isOk())
				// An object, not a string. `$.menuLayout.groups[0].id` cannot be satisfied by JSON
				// that was put on the wire as text, which is exactly why it is asserted this way:
				// the client's type says MenuLayout, and a string there would be a lie no compiler
				// on either side could catch.
				.andExpect(jsonPath("$.menuLayout.version").value(1))
				.andExpect(jsonPath("$.menuLayout.groups.length()").value(2))
				.andExpect(jsonPath("$.menuLayout.groups[0].id").value("top"))
				.andExpect(jsonPath("$.menuLayout.groups[0].title").doesNotExist())
				.andExpect(jsonPath("$.menuLayout.groups[0].items[0]").value("dashboard"))
				.andExpect(jsonPath("$.menuLayout.groups[1].id").value("kitchen"))
				.andExpect(jsonPath("$.menuLayout.groups[1].title").value("Kitchen"))
				// The order within a group is the whole feature; a set would pass a length check.
				.andExpect(jsonPath("$.menuLayout.groups[1].items[0]").value("meals"))
				.andExpect(jsonPath("$.menuLayout.groups[1].items[1]").value("recipes"))
				.andExpect(jsonPath("$.menuLayout.groups[1].items[2]").value("deliveries"));
	}

	@Test
	@DisplayName("a temple that has never arranged its menu says so, rather than being sent a standard one")
	void nullUntilArranged() throws Exception {
		signIn("uid-cook-a");

		// Null rather than today's standard menu written out. Which destinations exist and what
		// order they come in is the browser's business, and sending a copy from here would be a
		// second statement of it in the one place that cannot see whether it is still true.
		mvc.perform(authed(get("/api/v1/whoami")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.menuLayout").doesNotExist());
	}

	@Test
	@DisplayName("a settings row that exists for some other reason, with no arrangement on it")
	void settingsRowWithoutAnArrangement() throws Exception {
		// The shape that took the application down on 2026-08-30 for the theme: "has not arranged"
		// has two forms, and only the obvious one tends to get tested. No row at all gives an empty
		// result; a row written for some other preference gives a result holding one null, which is
		// what stream().findFirst() cannot survive.
		signIn("uid-admin-a");
		mvc.perform(authed(put("/api/v1/settings/volunteer-broadcast-limit"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"limit\":5}"))
				.andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/whoami")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.menuLayout").doesNotExist());
	}

	@Test
	@DisplayName("a platform operator belongs to no temple and has no arrangement of their own")
	void operatorHasNoArrangement() throws Exception {
		signIn("uid-admin-a");
		save(ARRANGEMENT).andExpect(status().isNoContent());

		signIn("uid-operator");
		// Without a special case: an operator carries no app.tenant_id, so the policy on
		// tenant_settings matches nothing and the read comes back empty on its own.
		mvc.perform(authed(get("/api/v1/whoami")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tenantId").doesNotExist())
				.andExpect(jsonPath("$.menuLayout").doesNotExist());
	}

	@Test
	@DisplayName("arranging again replaces the arrangement rather than adding to it")
	void savingTwiceReplaces() throws Exception {
		signIn("uid-admin-a");
		save(ARRANGEMENT).andExpect(status().isNoContent());
		save("""
				{"version":1,"groups":[{"id":"only","title":"Everything","items":["recipes"]}]}
				""").andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/whoami")))
				.andExpect(jsonPath("$.menuLayout.groups.length()").value(1))
				.andExpect(jsonPath("$.menuLayout.groups[0].id").value("only"))
				.andExpect(jsonPath("$.menuLayout.groups[0].items.length()").value(1))
				.andExpect(jsonPath("$.menuLayout.groups[0].items[0]").value("recipes"));

		// One row per temple however many times they rearrange, and the destinations of the first
		// arrangement are gone rather than merged into the second.
		assertThat(admin.queryForObject("SELECT count(*) FROM tenant_settings", Integer.class)).isEqualTo(1);
		assertThat(storedArrangement(templeA)).doesNotContain("dashboard").doesNotContain("kitchen");
	}

	// ------------------------------------------------------------------ putting the standard menu back

	@Test
	@DisplayName("reset puts the standard menu back, and says so again if asked again")
	void resetIsAlwaysAnAnswer() throws Exception {
		signIn("uid-admin-a");
		save(ARRANGEMENT).andExpect(status().isNoContent());

		mvc.perform(authed(delete("/api/v1/settings/menu-layout"))).andExpect(status().isNoContent());
		mvc.perform(authed(get("/api/v1/whoami")))
				.andExpect(jsonPath("$.menuLayout").doesNotExist());
		// Null, not today's standard arrangement written out: a temple that resets is asking to
		// follow the standard menu from here on, including the parts not yet decided (V154 §2).
		assertThat(storedArrangement(templeA)).isNull();

		// Again, with nothing left to clear. "It is already standard" is the outcome the person
		// asked for, so a 404 here would be the endpoint disagreeing with itself.
		mvc.perform(authed(delete("/api/v1/settings/menu-layout"))).andExpect(status().isNoContent());
	}

	@Test
	@DisplayName("reset succeeds for a temple that has never saved a setting of any kind")
	void resetWithNoSettingsRowAtAll() throws Exception {
		signIn("uid-admin-b");
		assertThat(admin.queryForObject("SELECT count(*) FROM tenant_settings", Integer.class)).isZero();

		mvc.perform(authed(delete("/api/v1/settings/menu-layout"))).andExpect(status().isNoContent());

		// And no row was written to record that a temple has stopped doing something it never
		// started. A plain UPDATE matching nothing is the honest statement, not an upsert.
		assertThat(admin.queryForObject("SELECT count(*) FROM tenant_settings", Integer.class)).isZero();
	}

	// ------------------------------------------------------------------ isolation

	@Test
	@DisplayName("one temple's menu is not another's")
	void perTemple() throws Exception {
		signIn("uid-admin-a");
		save(ARRANGEMENT).andExpect(status().isNoContent());

		// Read through the application's own unprivileged role, which is what makes this mean
		// anything: a superuser bypasses RLS entirely and would pass whatever the policy said.
		signIn("uid-admin-b");
		mvc.perform(authed(get("/api/v1/whoami")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.menuLayout").doesNotExist());

		// Temple B arranging its own menu leaves temple A's exactly where it was.
		save("""
				{"version":1,"groups":[{"id":"b","title":"Bee","items":["vendors"]}]}
				""").andExpect(status().isNoContent());

		signIn("uid-admin-a");
		mvc.perform(authed(get("/api/v1/whoami")))
				.andExpect(jsonPath("$.menuLayout.groups[1].id").value("kitchen"));
		assertThat(storedArrangement(templeB)).contains("vendors").doesNotContain("kitchen");
	}

	// ------------------------------------------------------------------ who may arrange

	@Test
	@DisplayName("nobody but the Temple Admin may arrange the temple's menu, or put it back")
	void onlyTheTempleAdminMayArrange() throws Exception {
		for (String uid : new String[] {"uid-manager-a", "uid-cook-a"}) {
			signIn(uid);
			save(ARRANGEMENT).andExpect(status().isForbidden());
			mvc.perform(authed(delete("/api/v1/settings/menu-layout")))
					.andExpect(status().isForbidden());
		}

		// And nothing they sent was stored.
		assertThat(admin.queryForObject("SELECT count(*) FROM tenant_settings", Integer.class)).isZero();
	}

	// ------------------------------------------------------------------ the two codes

	@Test
	@DisplayName("the same destination in two groups is refused as KMS-400190, and nothing is stored")
	void oneDestinationCannotBeInTwoGroups() throws Exception {
		signIn("uid-admin-a");

		save("""
				{"version":1,"groups":[
					{"id":"one","title":"One","items":["meals"]},
					{"id":"two","title":"Two","items":["recipes","meals"]}]}
				""")
				.andExpect(status().isConflict())
				// The code, not only the status. A bare status check passes just as happily against
				// the wrong code, and the number is what somebody quotes off an old screenshot.
				.andExpect(jsonPath("$.code").value("KMS-400190"))
				// And the repeated id stays in the log. The person is looking at the arrangement.
				.andExpect(jsonPath("$.message").value("A menu item can only be in one group."));

		assertThat(storedArrangement(templeA)).isNull();
	}

	@Test
	@DisplayName("the same destination twice in one group is the same refusal")
	void oneDestinationCannotBeInOneGroupTwice() throws Exception {
		signIn("uid-admin-a");

		save("""
				{"version":1,"groups":[{"id":"one","title":"One","items":["meals","meals"]}]}
				""")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400190"));
	}

	@Test
	@DisplayName("an arrangement that could not be acted on is refused as KMS-400189")
	void anUnreadableArrangementIsRefused() throws Exception {
		signIn("uid-admin-a");

		// A group id that could not be an id: capitals, spaces, a leading hyphen, and one longer
		// than anything the menu has ever used.
		for (String badId : new String[] {"Kitchen", "the kitchen", "-kitchen", "kitchen_area",
				"a".repeat(65)}) {
			refused("""
					{"version":1,"groups":[{"id":"%s","title":"Kitchen","items":["meals"]}]}
					""".formatted(badId));
		}

		// An item id with the same problems.
		refused("""
				{"version":1,"groups":[{"id":"kitchen","title":"Kitchen","items":["Meals"]}]}
				""");
		refused("""
				{"version":1,"groups":[{"id":"kitchen","title":"Kitchen","items":[""]}]}
				""");

		// Two groups claiming one id. The temple meant two groups; which one each item belongs to
		// is not something this side may guess.
		refused("""
				{"version":1,"groups":[
					{"id":"kitchen","title":"Kitchen","items":["meals"]},
					{"id":"kitchen","title":"Also kitchen","items":["recipes"]}]}
				""");

		// A version this release does not write. Deliberately a code rather than a field error:
		// there is no box on any screen that sets it, so "check the highlighted fields" would be
		// advice nobody can follow, while "arrange it again and save" is the thing that works.
		refused("""
				{"version":2,"groups":[{"id":"kitchen","title":"Kitchen","items":["meals"]}]}
				""");

		assertThat(storedArrangement(templeA)).isNull();
	}

	// ------------------------------------------------------------------ the bounds that are field errors

	@Test
	@DisplayName("a blank heading, a heading too long, and too many groups are field errors, not codes")
	void theBoundsAPersonCanSeeComeBackAgainstTheBox() throws Exception {
		signIn("uid-admin-a");

		// A heading of spaces. Null is a real answer here — the unheaded block at the top of the
		// standard menu has one — and the space bar is not the same answer.
		fieldError("""
				{"version":1,"groups":[{"id":"kitchen","title":"   ","items":["meals"]}]}
				""");

		// Forty-one characters, measured after trimming, which is what would be stored.
		fieldError("""
				{"version":1,"groups":[{"id":"kitchen","title":"%s","items":["meals"]}]}
				""".formatted("a".repeat(41)));

		// Twenty-one groups.
		String tooManyGroups = IntStream.rangeClosed(1, 21)
				.mapToObj(n -> "{\"id\":\"g%d\",\"title\":\"Group %d\",\"items\":[]}".formatted(n, n))
				.collect(Collectors.joining(","));
		fieldError("{\"version\":1,\"groups\":[" + tooManyGroups + "]}");

		// No groups at all is not an arrangement either.
		fieldError("{\"version\":1,\"groups\":[]}");

		assertThat(storedArrangement(templeA)).isNull();
	}

	@Test
	@DisplayName("twenty groups and a forty-character heading are accepted — the bounds are inclusive")
	void theBoundsAreInclusive() throws Exception {
		signIn("uid-admin-a");

		String twentyGroups = IntStream.rangeClosed(1, 20)
				.mapToObj(n -> "{\"id\":\"g%d\",\"title\":\"%s\",\"items\":[\"item-%d\"]}"
						.formatted(n, "a".repeat(40), n))
				.collect(Collectors.joining(","));

		save("{\"version\":1,\"groups\":[" + twentyGroups + "]}").andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/whoami")))
				.andExpect(jsonPath("$.menuLayout.groups.length()").value(20))
				.andExpect(jsonPath("$.menuLayout.groups[19].items[0]").value("item-20"));
	}

	@Test
	@DisplayName("a destination this side has never heard of is stored rather than argued with")
	void unknownDestinationsAreStored() throws Exception {
		signIn("uid-admin-a");

		// Deliberate, and the reason there is no list of destinations on this side: the menu lives
		// in frontend/lib/nav.ts, and an id that stops resolving — a screen renamed, a screen
		// retired — is passed over by the merge there. Refusing it here would mean a second copy of
		// the catalogue, kept in step by hand.
		save("""
				{"version":1,"groups":[{"id":"kitchen","title":"Kitchen","items":["a-screen-nobody-built"]}]}
				""").andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/whoami")))
				.andExpect(jsonPath("$.menuLayout.groups[0].items[0]").value("a-screen-nobody-built"));
	}

	// ---------------------------------------------------------------- helpers

	private org.springframework.test.web.servlet.ResultActions save(String body) throws Exception {
		return mvc.perform(authed(put("/api/v1/settings/menu-layout"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	/** Refused as an arrangement nobody could act on, with the permanent code on the response. */
	private void refused(String body) throws Exception {
		save(body)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400189"));
	}

	/** Refused against the box the person filled in, which is a better answer than a code. */
	private void fieldError(String body) throws Exception {
		save(body)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"))
				.andExpect(jsonPath("$.fieldErrors.length()").value(org.hamcrest.Matchers.greaterThan(0)));
	}

	/**
	 * What is actually on the row, read as the superuser so no policy can hide it.
	 *
	 * <p>Null for "no arrangement" and null for "no settings row", because both are the same fact
	 * to a reader — and because a temple that has been refused every time has no row to read.
	 */
	private String storedArrangement(UUID tenantId) {
		List<String> rows = admin.queryForList(
				"SELECT menu_layout::text FROM tenant_settings WHERE tenant_id = ?", String.class, tenantId);
		return rows.isEmpty() ? null : rows.get(0);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
		return builder.header("Authorization", "Bearer valid-token");
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}

	private UUID insertTenant(String slug, String name) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata') RETURNING id
				""", UUID.class, slug, name);
	}

	private void insertUser(UUID tenantId, String uid, String role, String phone) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, ?, ?, 'ACTIVE')
				""", tenantId, uid, uid + "@example.com", phone, role);
	}

	private void insertOperator(String uid) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (NULL, ?, 'Platform Operator', ?, '+919876500099', 'SUPER_ADMIN', 'ACTIVE')
				""", uid, uid + "@example.com");
	}
}
