package org.iskcon.kms.equipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
import org.iskcon.kms.tenancy.TenantAwareDataSource;
import org.iskcon.kms.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Equipment servicing (E3-S10) through the full stack.
 *
 * <p>Five things here cannot be proved without a real database and are the reason this class exists
 * rather than more unit tests beside {@link ServiceIntervalTest}.
 *
 * <p><b>That the record is genuinely permanent.</b> Not that the application declines to offer an
 * edit — that an editable service history is worth nothing on the day somebody is arguing about
 * whether the grinder was looked at. The refusal is checked against the application's own
 * credentials with no Java in the way, the way the stock ledger and the conduct notes are.
 *
 * <p><b>That "last serviced" is only ever read.</b> Checked structurally: there is no such column
 * anywhere on {@code equipment_items}, so no future endpoint can quietly start writing one.
 *
 * <p><b>That the permission split is real.</b> Kitchen staff register the grinder and mark it
 * broken, and are refused at both servicing doors — endpoint by endpoint rather than trusted to the
 * fact that they carry the same annotation.
 *
 * <p><b>That the horizon is the temple's own.</b> Changing the setting moves which machines are
 * amber, which is the acceptance criterion and is a round trip through {@code tenant_settings}.
 *
 * <p><b>That another temple's machines and services are invisible.</b> Row-level security is a
 * database behaviour and mocking it would prove nothing.
 */
@AutoConfigureMockMvc
@Import(EquipmentServicingIT.StubVerifierConfiguration.class)
class EquipmentServicingIT extends AbstractIntegrationTest {

	/** "Today" as the application computes it — the temple's day, not the server's. */
	private static final LocalDate TODAY = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));

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
		stubVerifier.reset();
		templeA = insertTenant("radha-govinda", "Sri Sri Radha Govinda Temple");
		templeB = insertTenant("radha-krishna", "Sri Sri Radha Krishna Temple");
		insertUser(templeA, "uid-admin-a", "admin-a@example.com", "TEMPLE_ADMIN");
		insertUser(templeA, "uid-staff-a", "staff-a@example.com", "KITCHEN_STAFF");
		insertUser(templeA, "uid-manager-a", "manager-a@example.com", "KITCHEN_MANAGER");
		insertUser(templeB, "uid-admin-b", "admin-b@example.com", "TEMPLE_ADMIN");
		signIn("uid-admin-a");
	}

	@AfterEach
	void tearDown() {
		// Through the superuser, which the append-only trigger deliberately does not stop — it
		// refuses kms_app and only kms_app. That distinction is itself under test below.
		admin.execute("DELETE FROM equipment_services");
		admin.execute("DELETE FROM equipment_state_changes");
		admin.execute("DELETE FROM equipment_items");
		admin.execute("DELETE FROM tenant_settings");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ---- The record itself -----------------------------------------------

	@Nested
	@DisplayName("recording a service")
	class Recording {

		@Test
		@DisplayName("writes a row carrying who recorded it, and shows in the item's history")
		void recordsWhoAndWhat() throws Exception {
			UUID grinder = createEquipment("Wet Grinder", TODAY.minusYears(2));

			recordService(grinder, TODAY.minusDays(10), null, "Replaced the drive belt", "1250.00")
					.andExpect(status().isCreated());

			mvc.perform(authed(get("/api/v1/equipment/{id}", grinder)))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.services.length()").value(1))
					.andExpect(jsonPath("$.services[0].servicedOn")
							.value(TODAY.minusDays(10).toString()))
					.andExpect(jsonPath("$.services[0].workDone").value("Replaced the drive belt"))
					.andExpect(jsonPath("$.services[0].costInr").value(1250.00))
					.andExpect(jsonPath("$.services[0].actorName").value("Test Person"))
					.andExpect(jsonPath("$.services[0].actorUserId").exists())
					// A service is not a change of condition, and does not pretend to be one:
					// the condition trail still holds only the registration.
					.andExpect(jsonPath("$.history.length()").value(1));

			assertThat(auditCount("EQUIPMENT_SERVICED")).isEqualTo(1);
		}

		@Test
		@DisplayName("last serviced is the newest row, whatever order the rows were entered in")
		void lastServicedIsTheNewestRow() throws Exception {
			UUID grinder = createEquipment("Wet Grinder", TODAY.minusYears(3));
			setSchedule(grinder, 6, "MONTHS", null).andExpect(status().isNoContent());

			// Entered out of order on purpose — somebody catching up on last year's invoices.
			recordService(grinder, TODAY.minusDays(200), null, "Annual service", null)
					.andExpect(status().isCreated());
			recordService(grinder, TODAY.minusDays(20), null, "Bearings", null)
					.andExpect(status().isCreated());
			recordService(grinder, TODAY.minusDays(400), null, "The one before that", null)
					.andExpect(status().isCreated());

			mvc.perform(authed(get("/api/v1/equipment/{id}", grinder)))
					.andExpect(jsonPath("$.equipment.lastServicedOn")
							.value(TODAY.minusDays(20).toString()))
					.andExpect(jsonPath("$.equipment.nextServiceOn")
							.value(TODAY.minusDays(20).plusDays(180).toString()))
					.andExpect(jsonPath("$.equipment.nextServiceBasis").value("SERVICED"))
					// Newest first, and the newest is by the day the work was done.
					.andExpect(jsonPath("$.services.length()").value(3))
					.andExpect(jsonPath("$.services[0].workDone").value("Bearings"));
		}

		@Test
		@DisplayName("there is no way to set last serviced directly")
		void thereIsNoLastServicedColumn() {
			// Structural, and the point of D2: an editable date is what this feature refused to
			// build. If a later change adds the column, this fails before anybody can write to it.
			assertThat(columnsOf("equipment_items"))
					.as("a last-serviced column would be a place somebody could type over history")
					.doesNotContain("last_serviced_on", "last_serviced", "next_service_date",
							"next_service_on");
		}

		@Test
		@DisplayName("a service dated in the future is refused with KMS-4016")
		void futureServiceRefused() throws Exception {
			UUID grinder = createEquipment("Wet Grinder", TODAY.minusYears(1));

			recordService(grinder, TODAY.plusDays(1), null, "Booked for next Tuesday", null)
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("KMS-4016"));

			// Today itself is fine — a service done this morning is a service that happened.
			recordService(grinder, TODAY, null, "This morning", null)
					.andExpect(status().isCreated());
		}

		@Test
		@DisplayName("the row cannot afterwards be edited or deleted, even with the app's own credentials")
		void servicesAreAppendOnly() throws Exception {
			UUID grinder = createEquipment("Wet Grinder", TODAY.minusYears(1));
			recordService(grinder, TODAY.minusDays(5), null, "Replaced the belt", "900.00")
					.andExpect(status().isCreated());

			// Through kms_app itself, with the tenant set so row-level security *permits* the
			// write. What then refuses it is the append-only trigger, which is the guarantee under
			// test — not RLS quietly matching zero rows.
			asApplication(templeA, app -> {
				assertThat(app.queryForObject(
						"SELECT count(*) FROM equipment_services", Integer.class)).isEqualTo(1);

				assertThatThrownBy(() -> app.update(
						"UPDATE equipment_services SET serviced_on = serviced_on - 1"))
						.as("append-only: UPDATE must be refused")
						.hasStackTraceContaining("append-only");

				assertThatThrownBy(() -> app.update("DELETE FROM equipment_services"))
						.as("append-only: DELETE must be refused")
						.hasStackTraceContaining("append-only");
			});
		}
	}

	// ---- The derivation, end to end ---------------------------------------

	@Nested
	@DisplayName("the next service date")
	class Derivation {

		@Test
		@DisplayName("six months and ninety days both produce the right next date")
		void bothIntervalsAreRight() throws Exception {
			UUID sixMonthly = createEquipment("Steam Boiler", TODAY.minusYears(2));
			UUID ninetyDaily = createEquipment("Wet Grinder", TODAY.minusYears(2));

			setSchedule(sixMonthly, 6, "MONTHS", null).andExpect(status().isNoContent());
			setSchedule(ninetyDaily, 90, "DAYS", null).andExpect(status().isNoContent());

			LocalDate serviced = TODAY.minusDays(30);
			recordService(sixMonthly, serviced, null, null, null).andExpect(status().isCreated());
			recordService(ninetyDaily, serviced, null, null, null).andExpect(status().isCreated());

			mvc.perform(authed(get("/api/v1/equipment/{id}", sixMonthly)))
					.andExpect(jsonPath("$.equipment.serviceIntervalDays").value(180))
					.andExpect(jsonPath("$.equipment.serviceIntervalUnit").value("MONTHS"))
					// The count the person typed, recovered from the stored day total.
					.andExpect(jsonPath("$.equipment.serviceIntervalCount").value(6))
					.andExpect(jsonPath("$.equipment.nextServiceOn")
							.value(serviced.plusDays(180).toString()));

			mvc.perform(authed(get("/api/v1/equipment/{id}", ninetyDaily)))
					.andExpect(jsonPath("$.equipment.serviceIntervalDays").value(90))
					.andExpect(jsonPath("$.equipment.serviceIntervalUnit").value("DAYS"))
					.andExpect(jsonPath("$.equipment.serviceIntervalCount").value(90))
					.andExpect(jsonPath("$.equipment.nextServiceOn")
							.value(serviced.plusDays(90).toString()));
		}

		@Test
		@DisplayName("a machine never serviced counts from its purchase date and says so")
		void derivesFromPurchase() throws Exception {
			LocalDate bought = TODAY.minusDays(100);
			UUID mixer = createEquipment("Industrial Mixer", bought);
			setSchedule(mixer, 1, "YEARS", null).andExpect(status().isNoContent());

			mvc.perform(authed(get("/api/v1/equipment/{id}", mixer)))
					.andExpect(jsonPath("$.equipment.lastServicedOn").doesNotExist())
					.andExpect(jsonPath("$.equipment.nextServiceOn")
							.value(bought.plusDays(365).toString()))
					.andExpect(jsonPath("$.equipment.nextServiceBasis").value("PURCHASED"))
					.andExpect(jsonPath("$.equipment.serviceStatus").value("OK"));
		}

		@Test
		@DisplayName("a machine with neither a service nor a purchase date reads not scheduled")
		void neitherDateReadsNotScheduled() throws Exception {
			UUID scale = createEquipment("Weighing Scale", null);
			setSchedule(scale, 1, "YEARS", null).andExpect(status().isNoContent());

			mvc.perform(authed(get("/api/v1/equipment/{id}", scale)))
					.andExpect(jsonPath("$.equipment.nextServiceOn").doesNotExist())
					.andExpect(jsonPath("$.equipment.nextServiceBasis").value("NONE"))
					.andExpect(jsonPath("$.equipment.serviceStatus").value("NOT_SCHEDULED"));

			// And in no warning count: the overdue list is empty even though the machine has an
			// interval and has never been serviced.
			mvc.perform(authed(get("/api/v1/equipment")).param("serviceStatus", "OVERDUE"))
					.andExpect(jsonPath("$.length()").value(0));
		}

		@Test
		@DisplayName("a machine nobody has set an interval for is not scheduled, not overdue")
		void noIntervalIsNotOverdue() throws Exception {
			createEquipment("Trestle Table", TODAY.minusYears(10));

			mvc.perform(authed(get("/api/v1/equipment")))
					.andExpect(jsonPath("$[0].serviceStatus").value("NOT_SCHEDULED"))
					.andExpect(jsonPath("$[0].nextServiceBasis").value("NONE"));

			mvc.perform(authed(get("/api/v1/equipment")).param("serviceStatus", "OVERDUE"))
					.andExpect(jsonPath("$.length()").value(0));
		}

		@Test
		@DisplayName("past the date is overdue, and the list filter finds exactly those")
		void overdueIsFilterable() throws Exception {
			UUID late = createEquipment("Steam Boiler", TODAY.minusYears(5));
			UUID fine = createEquipment("Wet Grinder", TODAY.minusYears(5));

			setSchedule(late, 30, "DAYS", null).andExpect(status().isNoContent());
			setSchedule(fine, 1, "YEARS", null).andExpect(status().isNoContent());

			recordService(late, TODAY.minusDays(60), null, null, null).andExpect(status().isCreated());
			recordService(fine, TODAY.minusDays(60), null, null, null).andExpect(status().isCreated());

			mvc.perform(authed(get("/api/v1/equipment/{id}", late)))
					.andExpect(jsonPath("$.equipment.serviceStatus").value("OVERDUE"));

			mvc.perform(authed(get("/api/v1/equipment")).param("serviceStatus", "OVERDUE"))
					.andExpect(jsonPath("$.length()").value(1))
					.andExpect(jsonPath("$[0].name").value("Steam Boiler"));
		}

		@Test
		@DisplayName("changing the temple's horizon changes which machines are amber")
		void horizonMovesTheAmberBand() throws Exception {
			// Due in fifty days: outside the default thirty-day horizon, inside a ninety-day one.
			UUID boiler = createEquipment("Steam Boiler", TODAY.minusYears(5));
			setSchedule(boiler, 60, "DAYS", null).andExpect(status().isNoContent());
			recordService(boiler, TODAY.minusDays(10), null, null, null).andExpect(status().isCreated());

			mvc.perform(authed(get("/api/v1/equipment/{id}", boiler)))
					.andExpect(jsonPath("$.equipment.serviceStatus").value("OK"));

			// Through the settings endpoint, so the round trip is the one a temple actually makes.
			mvc.perform(authed(put("/api/v1/settings/warning-horizons"))
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"stockExpiryWarningDays":7,"contractEndWarningDays":30,
									 "equipmentServiceWarningDays":90}"""))
					.andExpect(status().isNoContent());

			mvc.perform(authed(get("/api/v1/equipment/{id}", boiler)))
					.andExpect(jsonPath("$.equipment.serviceStatus").value("DUE_SOON"));

			mvc.perform(authed(get("/api/v1/equipment")).param("serviceStatus", "DUE_SOON"))
					.andExpect(jsonPath("$.length()").value(1));
		}

		@Test
		@DisplayName("the horizons are saved all three or not at all")
		void theHorizonsAreSavedAllThreeOrNotAtAll() throws Exception {
			mvc.perform(authed(put("/api/v1/settings/warning-horizons"))
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"stockExpiryWarningDays":7,"contractEndWarningDays":30,
									 "equipmentServiceWarningDays":90}"""))
					.andExpect(status().isNoContent());

			// This assertion used to send two horizons and prove the third survived untouched. That
			// leniency existed only because the settings form had no control for the third (E3-S10
			// D5); E3-S11 gave it one, so a body naming two is now a body that has left something out.
			mvc.perform(authed(put("/api/v1/settings/warning-horizons"))
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"stockExpiryWarningDays\":14,\"contractEndWarningDays\":45}"))
					.andExpect(status().isBadRequest());

			// And nothing moved. A refusal that had already written the first two would be worse than
			// either answer, because the temple would have no way of knowing which it got.
			mvc.perform(authed(get("/api/v1/settings")))
					.andExpect(jsonPath("$.stockExpiryWarningDays").value(7))
					.andExpect(jsonPath("$.contractEndWarningDays").value(30))
					.andExpect(jsonPath("$.equipmentServiceWarningDays").value(90));

			// All three named, and all three move.
			mvc.perform(authed(put("/api/v1/settings/warning-horizons"))
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"stockExpiryWarningDays":14,"contractEndWarningDays":45,
									 "equipmentServiceWarningDays":21}"""))
					.andExpect(status().isNoContent());

			mvc.perform(authed(get("/api/v1/settings")))
					.andExpect(jsonPath("$.stockExpiryWarningDays").value(14))
					.andExpect(jsonPath("$.contractEndWarningDays").value(45))
					.andExpect(jsonPath("$.equipmentServiceWarningDays").value(21));
		}

		@Test
		@DisplayName("a scrapped machine is in no service calculation and no overdue count")
		void scrappedIsNeverOverdue() throws Exception {
			UUID old = createEquipment("Old Mixer", TODAY.minusYears(10));
			setSchedule(old, 30, "DAYS", null).andExpect(status().isNoContent());
			recordService(old, TODAY.minusDays(500), null, null, null).andExpect(status().isCreated());

			// Years overdue on any other machine.
			mvc.perform(authed(get("/api/v1/equipment/{id}", old)))
					.andExpect(jsonPath("$.equipment.serviceStatus").value("OVERDUE"));

			mvc.perform(authed(post("/api/v1/equipment/{id}/condition", old))
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"condition\":\"SCRAPPED\",\"reason\":\"Motor burnt out\"}"))
					.andExpect(status().isNoContent());

			mvc.perform(authed(get("/api/v1/equipment/{id}", old)))
					.andExpect(jsonPath("$.equipment.serviceStatus").value("NOT_SCHEDULED"))
					.andExpect(jsonPath("$.equipment.nextServiceOn").doesNotExist())
					// The history it had is still there. Only the calculation stops.
					.andExpect(jsonPath("$.equipment.lastServicedOn")
							.value(TODAY.minusDays(500).toString()));

			// In no overdue count, even when scrapped items are explicitly asked for.
			mvc.perform(authed(get("/api/v1/equipment"))
							.param("serviceStatus", "OVERDUE").param("includeScrapped", "true"))
					.andExpect(jsonPath("$.length()").value(0));
		}
	}

	// ---- Serial numbers ---------------------------------------------------

	@Nested
	@DisplayName("serial numbers")
	class Serials {

		@Test
		@DisplayName("a duplicate serial is refused with KMS-4015, on create and on edit")
		void duplicateSerialRefused() throws Exception {
			create("""
					{"name":"Wet Grinder A","serialNumber":"WG-2019-114"}""")
					.andExpect(status().isCreated());

			create("""
					{"name":"Wet Grinder B","serialNumber":"WG-2019-114"}""")
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.code").value("KMS-4015"));

			// And the same on the way through an edit, which is the other door into the column.
			UUID other = createEquipment("Steam Boiler", null);
			mvc.perform(authed(put("/api/v1/equipment/{id}", other))
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"name":"Steam Boiler",
									 "serialNumber":"WG-2019-114"}"""))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.code").value("KMS-4015"));
		}

		@Test
		@DisplayName("a blank serial is allowed on any number of rows")
		void blankSerialsDoNotCollide() throws Exception {
			// A trestle table has none, and the index is partial for exactly this reason.
			create("{\"name\":\"Trestle Table 1\"}")
					.andExpect(status().isCreated());
			create("{\"name\":\"Trestle Table 2\"}")
					.andExpect(status().isCreated());
			create("""
					{"name":"Trestle Table 3","serialNumber":"  "}""")
					.andExpect(status().isCreated());

			mvc.perform(authed(get("/api/v1/equipment")))
					.andExpect(jsonPath("$.length()").value(3));
		}

		@Test
		@DisplayName("another temple may use the same serial")
		void serialsAreUniquePerTemple() throws Exception {
			create("""
					{"name":"Wet Grinder","serialNumber":"WG-2019-114"}""")
					.andExpect(status().isCreated());

			signIn("uid-admin-b");
			create("""
					{"name":"Wet Grinder","serialNumber":"WG-2019-114"}""")
					.andExpect(status().isCreated());
		}
	}

	// ---- The service company ----------------------------------------------

	@Nested
	@DisplayName("the service company")
	class Companies {

		@Test
		@DisplayName("is text on the machine, and there is no list behind it")
		void isPlainTextOnTheMachine() throws Exception {
			UUID grinder = createEquipment("Wet Grinder", TODAY.minusYears(1));
			UUID boiler = createEquipment("Steam Boiler", TODAY.minusYears(1));

			setSchedule(grinder, 6, "MONTHS", "Sharma Engineering", "+919845012345")
					.andExpect(status().isNoContent());
			setSchedule(boiler, 1, "YEARS", "Sharma Engineering", "+919845012345")
					.andExpect(status().isNoContent());

			// One company on two machines, and the fact is on each row rather than in a list they
			// both point at. That is the whole of the 2026-09-04 reversal (D7).
			for (UUID machine : new UUID[] {grinder, boiler}) {
				mvc.perform(authed(get("/api/v1/equipment/{id}", machine)))
						.andExpect(jsonPath("$.equipment.serviceCompany").value("Sharma Engineering"))
						.andExpect(jsonPath("$.equipment.serviceCompanyPhone").value("+919845012345"));
			}

			// Structural, so no future change quietly builds the list again: the table is gone,
			// and neither register row carries a reference to one.
			assertThat(admin.queryForObject("""
					SELECT to_regclass('public.service_providers') IS NULL
					""", Boolean.class)).isTrue();
			assertThat(columnsOf("equipment_items")).doesNotContain("service_provider_id");
			assertThat(columnsOf("equipment_services")).doesNotContain("service_provider_id");
		}

		@Test
		@DisplayName("is remembered on the visit, and does not move when the machine's company does")
		void theVisitKeepsWhoCame() throws Exception {
			UUID grinder = createEquipment("Wet Grinder", TODAY.minusYears(1));
			setSchedule(grinder, 6, "MONTHS", "Sharma Engineering", "+919845012345")
					.andExpect(status().isNoContent());

			recordService(grinder, TODAY.minusDays(5), "Iyer Repairs", "Replaced the belt", "900.00")
					.andExpect(status().isCreated());

			// The contract moves to somebody else. The visit still says who actually turned up —
			// which is why the name is copied onto the row and not reached through it.
			setSchedule(grinder, 6, "MONTHS", "Bengaluru Kitchen Engineering", "+919845099999")
					.andExpect(status().isNoContent());

			mvc.perform(authed(get("/api/v1/equipment/{id}", grinder)))
					.andExpect(jsonPath("$.equipment.serviceCompany")
							.value("Bengaluru Kitchen Engineering"))
					.andExpect(jsonPath("$.services[0].serviceCompany").value("Iyer Repairs"));
		}

		@Test
		@DisplayName("clears when it is emptied, and is never required")
		void clearsAndIsOptional() throws Exception {
			UUID grinder = createEquipment("Wet Grinder", TODAY.minusYears(1));
			setSchedule(grinder, 6, "MONTHS", "Sharma Engineering", "+919845012345")
					.andExpect(status().isNoContent());

			// Blank, not absent: the box was emptied, which a temple whose contract has lapsed is
			// entitled to say. Whitespace counts as empty.
			setSchedule(grinder, 6, "MONTHS", "   ", "  ").andExpect(status().isNoContent());

			mvc.perform(authed(get("/api/v1/equipment/{id}", grinder)))
					.andExpect(jsonPath("$.equipment.serviceCompany").doesNotExist())
					.andExpect(jsonPath("$.equipment.serviceCompanyPhone").doesNotExist());

			// And a visit by the temple's own fitter names nobody at all.
			recordService(grinder, TODAY.minusDays(1), null, "Tightened the belt", null)
					.andExpect(status().isCreated());
			mvc.perform(authed(get("/api/v1/equipment/{id}", grinder)))
					.andExpect(jsonPath("$.services[0].serviceCompany").doesNotExist());
		}
	}

	// ---- Who may do what --------------------------------------------------

	@Nested
	@DisplayName("the permission split")
	class Permissions {

		@Test
		@DisplayName("kitchen staff register equipment and change its condition")
		void staffKeepTheRegister() throws Exception {
			// D10: they are the ones standing in front of the grinder when it stops.
			signIn("uid-staff-a");

			UUID grinder = createEquipment("Wet Grinder", TODAY.minusYears(1));

			mvc.perform(authed(post("/api/v1/equipment/{id}/condition", grinder))
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"condition\":\"NEEDS_REPAIR\",\"reason\":\"Belt slipping\"}"))
					.andExpect(status().isNoContent());

			mvc.perform(authed(get("/api/v1/equipment/{id}", grinder)))
					.andExpect(status().isOk());
		}

		@Test
		@DisplayName("kitchen staff cannot set an interval or record a service")
		void staffCannotService() throws Exception {
			UUID grinder = createEquipment("Wet Grinder", TODAY.minusYears(1));
			signIn("uid-staff-a");

			setSchedule(grinder, 6, "MONTHS", null).andExpect(status().isForbidden());
			recordService(grinder, TODAY.minusDays(1), null, "Belt", null)
					.andExpect(status().isForbidden());
		}

		@Test
		@DisplayName("a kitchen manager cannot either")
		void managerCannotService() throws Exception {
			UUID grinder = createEquipment("Wet Grinder", TODAY.minusYears(1));
			signIn("uid-manager-a");

			setSchedule(grinder, 6, "MONTHS", null).andExpect(status().isForbidden());
			recordService(grinder, TODAY.minusDays(1), null, "Belt", null)
					.andExpect(status().isForbidden());
		}

		@Test
		@DisplayName("staff still read the derived service fields, because reading is not an act")
		void staffStillSeeWhenItIsDue() throws Exception {
			UUID grinder = createEquipment("Wet Grinder", TODAY.minusYears(5));
			setSchedule(grinder, 30, "DAYS", null).andExpect(status().isNoContent());
			recordService(grinder, TODAY.minusDays(60), null, null, null).andExpect(status().isCreated());

			signIn("uid-staff-a");
			mvc.perform(authed(get("/api/v1/equipment/{id}", grinder)))
					.andExpect(jsonPath("$.equipment.serviceStatus").value("OVERDUE"));
		}
	}

	// ---- Isolation --------------------------------------------------------

	@Nested
	@DisplayName("another temple")
	class Isolation {

		@Test
		@DisplayName("cannot see or write this temple's equipment or services")
		void rlsScopesEverything() throws Exception {
			UUID grinder = createEquipment("Wet Grinder", TODAY.minusYears(1));
			setSchedule(grinder, 6, "MONTHS", "Sharma Engineering", "+919845012345")
					.andExpect(status().isNoContent());
			recordService(grinder, TODAY.minusDays(5), "Sharma Engineering", "Replaced the belt",
					"900.00").andExpect(status().isCreated());

			signIn("uid-admin-b");

			// Invisible.
			mvc.perform(authed(get("/api/v1/equipment"))).andExpect(jsonPath("$.length()").value(0));
			mvc.perform(authed(get("/api/v1/equipment/{id}", grinder)))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.code").value("KMS-4402"));

			// And un-writable: the machine cannot be reached by id.
			recordService(grinder, TODAY.minusDays(1), null, "Meddling", null)
					.andExpect(status().isNotFound());
			setSchedule(grinder, 1, "YEARS", null).andExpect(status().isNotFound());
		}

		@Test
		@DisplayName("another temple's services do not count towards this one's last serviced")
		void foreignServicesDoNotLeak() throws Exception {
			UUID grinder = createEquipment("Wet Grinder", TODAY.minusYears(1));
			setSchedule(grinder, 6, "MONTHS", null).andExpect(status().isNoContent());

			// A service row pointing at this temple's machine but owned by the other temple.
			// Only possible with the superuser; the point is that the derivation cannot see it.
			UUID adminB = admin.queryForObject(
					"SELECT id FROM users WHERE firebase_uid = 'uid-admin-b'", UUID.class);
			admin.update("""
					INSERT INTO equipment_services (tenant_id, equipment_id, serviced_on, actor_user_id)
					VALUES (?, ?, ?, ?)
					""", templeB, grinder, TODAY.minusDays(1), adminB);

			mvc.perform(authed(get("/api/v1/equipment/{id}", grinder)))
					.andExpect(jsonPath("$.equipment.lastServicedOn").doesNotExist())
					.andExpect(jsonPath("$.equipment.nextServiceBasis").value("PURCHASED"))
					.andExpect(jsonPath("$.services.length()").value(0));
		}
	}

	// ---------------------------------------------------------------------

	private org.springframework.test.web.servlet.ResultActions create(String json) throws Exception {
		return mvc.perform(authed(post("/api/v1/equipment"))
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private UUID createEquipment(String name, LocalDate acquired) throws Exception {
		String json = "{\"name\":\"" + name + "\""
				+ (acquired == null ? "" : ",\"acquisitionDate\":\"" + acquired + "\"") + "}";
		String body = create(json)
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return idOf(body);
	}

	private org.springframework.test.web.servlet.ResultActions setSchedule(
			UUID equipmentId, Integer count, String unit, String company) throws Exception {
		return setSchedule(equipmentId, count, unit, company, null);
	}

	private org.springframework.test.web.servlet.ResultActions setSchedule(
			UUID equipmentId, Integer count, String unit, String company, String phone)
			throws Exception {

		String json = "{"
				+ (count == null ? "" : "\"intervalCount\":" + count + ",")
				+ (unit == null ? "" : "\"intervalUnit\":\"" + unit + "\",")
				+ "\"serviceCompany\":" + (company == null ? "null" : "\"" + company + "\"")
				+ (phone == null ? "" : ",\"serviceCompanyPhone\":\"" + phone + "\"")
				+ "}";
		return mvc.perform(authed(put("/api/v1/equipment/{id}/service-schedule", equipmentId))
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private org.springframework.test.web.servlet.ResultActions recordService(
			UUID equipmentId, LocalDate servicedOn, String company, String workDone, String cost)
			throws Exception {

		String json = "{\"servicedOn\":\"" + servicedOn + "\""
				+ (company == null ? "" : ",\"serviceCompany\":\"" + company + "\"")
				+ (workDone == null ? "" : ",\"workDone\":\"" + workDone + "\"")
				+ (cost == null ? "" : ",\"costInr\":" + cost)
				+ "}";
		return mvc.perform(authed(post("/api/v1/equipment/{id}/services", equipmentId))
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private static UUID idOf(String body) {
		return UUID.fromString(body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
		return builder.header("Authorization", "Bearer valid-token");
	}

	private java.util.List<String> columnsOf(String table) {
		return admin.queryForList("""
				SELECT attname FROM pg_attribute
				WHERE attrelid = format('public.%I', ?)::regclass AND attnum > 0 AND NOT attisdropped
				""", String.class, table);
	}

	/** Runs statements as the unprivileged application role, scoped to one tenant. */
	private void asApplication(UUID tenant, Consumer<JdbcTemplate> work) {
		DriverManagerDataSource plain = new DriverManagerDataSource();
		plain.setUrl(POSTGRES.getJdbcUrl());
		plain.setUsername(APP_ROLE);
		plain.setPassword(APP_PASSWORD);

		TenantContext.set(tenant);
		try {
			work.accept(new JdbcTemplate(new TenantAwareDataSource(plain)));
		} finally {
			TenantContext.clear();
		}
	}

	private int auditCount(String action) {
		Integer c = admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = ?", Integer.class, action);
		return c == null ? 0 : c;
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}

	private UUID insertTenant(String slug, String name) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class, slug, name);
	}

	private int nextPhone = 1;

	private void insertUser(UUID tenantId, String uid, String email, String role) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, ?, ?, 'ACTIVE')
				""", tenantId, uid, email, String.format("+91987650%04d", nextPhone++), role);
	}

	// ---------------------------------------------------------------------

	@TestConfiguration
	static class StubVerifierConfiguration {

		@Bean
		@Primary
		StubTokenVerifier stubTokenVerifier() {
			return new StubTokenVerifier();
		}
	}

	static class StubTokenVerifier implements TokenVerifier {

		private final Map<String, VerifiedSubject> accepted = new HashMap<>();

		void accept(String uid) {
			accepted.put("valid-token", new VerifiedSubject(uid, uid + "@example.com", "+919000000000"));
		}

		void reset() {
			accepted.clear();
		}

		@Override
		public VerifiedSubject verify(String idToken) throws InvalidTokenException {
			VerifiedSubject subject = accepted.get(idToken);
			if (subject == null) {
				throw new InvalidTokenException("Unrecognised token");
			}
			return subject;
		}
	}
}
