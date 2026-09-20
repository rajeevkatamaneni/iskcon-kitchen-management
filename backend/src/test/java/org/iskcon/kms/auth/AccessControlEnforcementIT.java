package org.iskcon.kms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.testsupport.StubTokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Verifies that permissions are actually enforced over HTTP, not merely declared.
 *
 * <p>{@link RolePermissionsTest} asserts the policy is correct; this asserts the application
 * obeys it. Both are needed — a correct policy that no endpoint consults protects nothing.
 *
 * <p>Endpoints here are defined by the test rather than borrowed from the application, so this
 * exercises the enforcement mechanism itself and does not break every time a real endpoint moves.
 */
@Import(AccessControlEnforcementIT.TestEndpoints.class)
class AccessControlEnforcementIT extends AbstractIntegrationTest {

	@Autowired
	private TestRestTemplate rest;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@LocalServerPort
	private int port;

	private JdbcTemplate admin;
	private UUID tenantId;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());

		tenantId = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Sri Sri Radha Govinda Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("kitchen staff may manage inventory")
	void kitchenStaffMayManageInventory() {
		signInAs("KITCHEN_STAFF");

		assertThat(get("/test/inventory").getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	@DisplayName("kitchen staff may not touch vendor payments")
	void kitchenStaffMayNotPayVendors() {
		// The story's named example. Money is not a kitchen concern.
		signInAs("KITCHEN_STAFF");

		assertThat(get("/test/payments").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	@DisplayName("a volunteer may not touch vendor payments")
	void volunteerMayNotPayVendors() {
		signInAs("VOLUNTEER");

		ResponseEntity<String> response = get("/test/payments");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(response.getBody())
				.as("the response must not disclose which permission was missing")
				.doesNotContain("MANAGE_VENDOR_PAYMENTS");
	}

	@Test
	@DisplayName("a volunteer may view their own shifts")
	void volunteerMayViewOwnShifts() {
		signInAs("VOLUNTEER");

		assertThat(get("/test/my-shifts").getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	@DisplayName("a temple admin may pay vendors")
	void templeAdminMayPayVendors() {
		signInAs("TEMPLE_ADMIN");

		assertThat(get("/test/payments").getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	@DisplayName("a temple admin may not provision tenants")
	void templeAdminMayNotProvisionTenants() {
		// Running a temple is not running the platform.
		signInAs("TEMPLE_ADMIN");

		assertThat(get("/test/tenants").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	@DisplayName("a temple admin may record equipment servicing")
	void templeAdminMayServiceEquipment() {
		signInAs("TEMPLE_ADMIN");

		assertThat(get("/test/equipment-servicing").getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	@DisplayName("kitchen staff may register equipment but may not record a service on it")
	void kitchenStaffMayNotServiceEquipment() {
		// The split E3-S10 D10 turns on, over HTTP rather than in the policy: the same person who
		// may add the wet grinder and mark it broken may not decide it is serviced every six
		// months, nor record that an engineer came and what they charged.
		signInAs("KITCHEN_STAFF");

		assertThat(get("/test/inventory").getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(get("/test/equipment-servicing").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	@DisplayName("a kitchen manager may not record a service either")
	void kitchenManagerMayNotServiceEquipment() {
		// Held by the Temple Admin alone — running the kitchen's people is not the same as
		// committing the temple's money to a maintenance contract.
		signInAs("KITCHEN_MANAGER");

		assertThat(get("/test/equipment-servicing").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	@DisplayName("a temple admin may say what the temple never buys, and a kitchen manager may not")
	void onlyTempleAdminSetsBuyingPolicy() {
		// T-402, over HTTP rather than in the policy. The manager is the interesting refusal: they
		// hold MANAGE_PURCHASE_ORDERS and build the temple's orders from the shopping list, so this
		// is the one place where "runs the ordering" and "decides what may never be ordered" come
		// apart. Asserted as a pair, because a refusal on its own would also pass if the endpoint
		// refused everybody.
		signInAs("TEMPLE_ADMIN");
		assertThat(get("/test/buying-policy").getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	@DisplayName("a kitchen manager may raise orders but may not say what the temple never buys")
	void kitchenManagerMayNotSetBuyingPolicy() {
		signInAs("KITCHEN_MANAGER");

		assertThat(get("/test/orders").getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(get("/test/buying-policy").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	@DisplayName("a volunteer sending a body that fails validation is refused, not told how to fix it")
	void volunteerWithInvalidBodyIsRefusedFirst() {
		// T-301. @Valid runs while Spring builds the method's arguments, which is before method
		// security can run, so this used to answer 400 with the form's field errors. The permission
		// question is now asked first, by PermissionFirstInterceptor.
		signInAs("VOLUNTEER");

		ResponseEntity<String> response = post("/test/payments", "{}");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(response.getBody()).contains("KMS-400021").doesNotContain("\"field\":\"reference\"");
	}

	@Test
	@DisplayName("a temple admin sending the same body still gets the field errors")
	void templeAdminWithInvalidBodyGetsFieldErrors() {
		signInAs("TEMPLE_ADMIN");

		ResponseEntity<String> response = post("/test/payments", "{}");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).contains("KMS-400001").contains("\"field\":\"reference\"");
	}

	@Test
	@DisplayName("an unauthenticated caller with an invalid body still gets 401")
	void unauthenticatedWithInvalidBodyIsUnauthorized() {
		assertThat(post("/test/payments", "{}").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	@DisplayName("an expression that reads an argument is left to method security, which still refuses")
	void argumentExpressionIsLeftToMethodSecurity() {
		// It cannot be answered before the arguments exist, so the interceptor skips it. Method
		// security is still switched on and still says no to a well-formed request.
		signInAs("VOLUNTEER");

		assertThat(post("/test/by-argument/anything", "{\"reference\": \"x\"}").getStatusCode())
				.isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	@DisplayName("an unauthenticated caller gets 401, not 403")
	void unauthenticatedIsUnauthorized() {
		// The distinction is worth keeping: 401 means "tell me who you are", 403 means "I know
		// who you are and the answer is no". Conflating them makes client behaviour guesswork.
		assertThat(get("/test/inventory").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	// ---------------------------------------------------------------------

	private void signInAs(String role) {
		String uid = "uid-" + role.toLowerCase();
		String email = role.toLowerCase() + "@example.com";
		String phone = "+9190000000" + (10 + role.length() % 80);

		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')
				""", tenantId, uid, "Test " + role, email, phone, role);

		stubVerifier.accept(uid, email, phone);
	}

	private ResponseEntity<String> get(String path) {
		HttpHeaders headers = new HttpHeaders();
		if (!stubVerifier.isEmpty()) {
			headers.setBearerAuth("valid-token");
		}
		return rest.exchange(
				"http://localhost:" + port + path,
				HttpMethod.GET,
				new HttpEntity<>(headers),
				String.class);
	}

	private ResponseEntity<String> post(String path, String json) {
		HttpHeaders headers = new HttpHeaders();
		if (!stubVerifier.isEmpty()) {
			headers.setBearerAuth("valid-token");
		}
		headers.setContentType(MediaType.APPLICATION_JSON);
		return rest.exchange(
				"http://localhost:" + port + path,
				HttpMethod.POST,
				new HttpEntity<>(json, headers),
				String.class);
	}

	/** A body with one required field, so that {@code {}} fails {@code @Valid}. */
	record PaymentBody(@NotBlank(message = "Enter a reference.") String reference) {
	}

	// ---------------------------------------------------------------------

	@RestController
	@RequestMapping("/test")
	static class TestEndpoints {

		@GetMapping("/inventory")
		@PreAuthorize("hasAuthority('MANAGE_INVENTORY')")
		String inventory() {
			return "ok";
		}

		@GetMapping("/payments")
		@PreAuthorize("hasAuthority('MANAGE_VENDOR_PAYMENTS')")
		String payments() {
			return "ok";
		}

		@GetMapping("/my-shifts")
		@PreAuthorize("hasAuthority('VIEW_OWN_SHIFTS')")
		String myShifts() {
			return "ok";
		}

		@GetMapping("/tenants")
		@PreAuthorize("hasAuthority('MANAGE_TENANTS')")
		String tenants() {
			return "ok";
		}

		@PostMapping("/payments")
		@PreAuthorize("hasAuthority('MANAGE_VENDOR_PAYMENTS')")
		String recordPayment(@Valid @RequestBody PaymentBody body) {
			return "ok";
		}

		// Refers to its argument, which nobody's authorities can satisfy: the interceptor must skip
		// it rather than evaluate it against arguments that do not exist yet.
		@PostMapping("/by-argument/{name}")
		@PreAuthorize("hasAuthority('MANAGE_TENANTS') and #name == 'never'")
		String byArgument(@PathVariable String name, @Valid @RequestBody PaymentBody body) {
			return "ok";
		}

		@GetMapping("/equipment-servicing")
		@PreAuthorize("hasAuthority('MANAGE_EQUIPMENT_SERVICING')")
		String equipmentServicing() {
			return "ok";
		}

		@GetMapping("/buying-policy")
		@PreAuthorize("hasAuthority('MANAGE_BUYING_POLICY')")
		String buyingPolicy() {
			return "ok";
		}

		@GetMapping("/orders")
		@PreAuthorize("hasAuthority('MANAGE_PURCHASE_ORDERS')")
		String orders() {
			return "ok";
		}
	}
}
