package org.iskcon.kms.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.notification.SendWhatsAppTestRequest;
import org.iskcon.kms.staff.HireStaffRequest;
import org.iskcon.kms.staff.UpdateStaffRequest;
import org.iskcon.kms.tenant.JoinTempleRequest;
import org.iskcon.kms.tenant.ProvisionTenantRequest;
import org.iskcon.kms.vendor.CreateVendorRequest;
import org.iskcon.kms.vendor.UpdateVendorRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A phone number typed the way {@code KMS-400003} itself writes one is accepted, on every field held
 * to E.164, and a wrong one is still wrong (T-157).
 *
 * <p>{@code KMS-400003} says "Include the country code, for example +91 98765 43210", and that example
 * used to be refused on all nine fields that could produce the message (two on each staff record,
 * and one each on joining, provisioning, the two vendor records and the WhatsApp test). {@code PhoneNumberDeserializer}
 * now removes separators before Bean Validation looks, and this class pins it on each of those
 * fields by name: a tenth phone field that forgets the annotation is not caught by a compiler, so the
 * list below is where somebody adding one should see the other nine.
 *
 * <p><strong>Why through the application's own {@code ObjectMapper} and {@code Validator}, rather
 * than posting to seven endpoints.</strong> Those are the two halves of the request pipeline the fix
 * lives in — Jackson builds the record, then validation reads it — and driving them directly lets one
 * class cover every field against every way of typing a number without a stub token verifier, a
 * tenant, or an application context of its own; this class has no {@code @Import} and shares the
 * default context, which matters for the suite's memory. What it does not show is the stored row and
 * the response code, so those are proven where each endpoint already has its test: {@code VendorIT},
 * {@code StaffEmploymentIT}, {@code MembershipIT}, {@code TenantProvisioningIT} and
 * {@code WhatsAppTestSendIT} post a spaced number and read back what was stored or sent, and
 * {@code PhoneValidationIT} shows a spaced but malformed number still reaching the reader as
 * {@code KMS-400003}.
 */
class PhoneNormalisationIT extends AbstractIntegrationTest {

	/** The rule {@code GlobalExceptionHandler} keys {@code KMS-400003} on. */
	private static final String E164_RULE = "^\\+[1-9][0-9]{7,14}$";

	private static final String CANONICAL = "+919876543210";

	@Autowired
	private ObjectMapper json;

	@Autowired
	private Validator validator;

	/**
	 * One E.164 field: which record, which component, a body that is valid in every other respect
	 * (with {@code %s} where the number goes, already JSON-encoded), and whether the number is required.
	 */
	record PhoneField(Class<?> type, String component, String body, boolean required) {

		@Override
		public String toString() {
			return type.getSimpleName() + "." + component;
		}
	}

	static List<PhoneField> theNineFields() {
		String hire = """
				{"fullName":"Lakshmi Devi","jobTitle":"COOK","employmentType":"FULL_TIME",
				 "dateOfJoining":"2026-01-10","%s":%%s}
				""";
		String vendor = """
				{"name":"Govind Wholesale","phone":%s}
				""";
		return List.of(
				new PhoneField(HireStaffRequest.class, "phone", hire.formatted("phone"), false),
				new PhoneField(HireStaffRequest.class, "emergencyContactPhone",
						hire.formatted("emergencyContactPhone"), false),
				new PhoneField(UpdateStaffRequest.class, "phone", hire.formatted("phone"), false),
				new PhoneField(UpdateStaffRequest.class, "emergencyContactPhone",
						hire.formatted("emergencyContactPhone"), false),
				new PhoneField(JoinTempleRequest.class, "phone", """
						{"firstName":"Nitai","lastName":"Das","phone":%s}
						""", true),
				new PhoneField(ProvisionTenantRequest.class, "adminPhone", """
						{"name":"Sri Sri Radha Krishna Temple","slug":"radha-krishna",
						 "address":"Bengaluru, Karnataka","latitude":12.9716,"longitude":77.5946,
						 "timezone":"Asia/Kolkata","currency":"INR","is80gApproved":true,
						 "adminName":"Gopal Das","adminEmail":"gopal@example.com","adminPhone":%s}
						""", true),
				new PhoneField(CreateVendorRequest.class, "phone", vendor, false),
				new PhoneField(UpdateVendorRequest.class, "phone", vendor, false),
				new PhoneField(SendWhatsAppTestRequest.class, "phoneNumber", """
						{"phoneNumber":%s}
						""", true));
	}

	/**
	 * The ways one number arrives in a box. The same characters are in {@code frontend/__tests__/phone.test.ts},
	 * because the two sides must remove the same set.
	 */
	static List<String> theSameNumberTyped() {
		// Escaped rather than pasted, so an editor cannot quietly turn them back into plain spaces.
		return List.of(
				"+91 98765 43210",                          // KMS-400003's own example
				"+91-98765-43210",                          // hyphens
				"+91 98765\u201343210",                     // an en dash, which a phone keyboard makes of a hyphen
				"\u00A0+91\u00A098765\u00A043210",          // non-breaking spaces, from autofill or a document
				"+91\u200B98765\u200B43210\uFEFF",          // zero-width spaces and a byte-order mark, from a paste
				"\u202A+91 98765 43210\u202C",              // the direction marks Android wraps a copied contact in
				"\t+91 98765 43210\n");                     // a tab and a newline, from a spreadsheet cell
	}

	static Stream<Arguments> everyFieldTypedEveryWay() {
		return theNineFields().stream()
				.flatMap(field -> theSameNumberTyped().stream().map(typed -> Arguments.of(field, typed)));
	}

	static Stream<PhoneField> everyField() {
		return theNineFields().stream();
	}

	static Stream<PhoneField> everyRequiredField() {
		return theNineFields().stream().filter(PhoneField::required);
	}

	// ------------------------------------------------------------------

	@ParameterizedTest(name = "{0} accepts {1}")
	@MethodSource("everyFieldTypedEveryWay")
	@DisplayName("a number with spaces, dashes or invisible characters is accepted, and carried as the bare number")
	void aSpacedNumberIsTheSameNumber(PhoneField field, String typed) throws Exception {
		Object request = read(field, typed);

		assertThat(valueOf(request, field))
				.as("what validation sees and the service stores, for %s", field)
				.isEqualTo(CANONICAL);
		assertThat(validator.validate(request)).as("violations on %s", field).isEmpty();
	}

	@ParameterizedTest(name = "{0} still refuses a typo")
	@MethodSource("everyField")
	@DisplayName("a malformed number is still refused by the phone rule, spaces or not, and never quietly repaired")
	void aMalformedNumberIsStillMalformed(PhoneField field) throws Exception {
		// A letter where a digit belongs. The provisioning screen's old cleaning kept only the plus and
		// the digits, which turned this into a well-formed number belonging to somebody else; the
		// separators go and the letter stays, so this has to fail the rule.
		assertOnlyThePhoneRuleFails(field, "+91 98765 4321X");

		// Brackets are deliberately not separators: "(0)" is a trunk prefix meant to be dropped, and
		// keeping the 0 while losing the brackets would store a number that passes and rings nobody.
		assertOnlyThePhoneRuleFails(field, "+91 (0) 98765 43210");

		// No country code is no country code, however it is spaced.
		assertOnlyThePhoneRuleFails(field, "98765 43210");
	}

	@ParameterizedTest(name = "{0}: a box of spaces is a missing number")
	@MethodSource("everyRequiredField")
	@DisplayName("where a number is required, a box of spaces is still a missing number, not a malformed one")
	void aBoxOfSpacesIsStillMissing(PhoneField field) throws Exception {
		Object request = read(field, "   ");

		// Empty, never null: null would skip @Pattern and change what an optional field means.
		assertThat((String) valueOf(request, field)).isEmpty();
		// @NotBlank still fires, so the handler sees a second kind of failure and keeps KMS-400001
		// with "Enter a phone number." — the boundary PhoneValidationIT holds at the endpoint.
		assertThat(violationsOn(validator.validate(request), field).stream()
				.anyMatch(v -> v.getConstraintDescriptor().getAnnotation() instanceof NotBlank))
				.as("a @NotBlank violation on %s", field)
				.isTrue();
	}

	@ParameterizedTest(name = "{0}: null stays null")
	@MethodSource("everyField")
	@DisplayName("a number that was not sent at all is still not sent")
	void nullIsNotAnEmptyNumber(PhoneField field) throws Exception {
		Object request = json.readValue(field.body().formatted("null"), field.type());
		assertThat(valueOf(request, field)).isNull();
	}

	// ------------------------------------------------------------------

	private void assertOnlyThePhoneRuleFails(PhoneField field, String typed) throws Exception {
		Set<? extends ConstraintViolation<?>> violations = validator.validate(read(field, typed));

		assertThat(violations).as("violations on %s for %s", field, typed).hasSize(1);
		ConstraintViolation<?> only = violations.iterator().next();
		assertThat(only.getPropertyPath().toString()).isEqualTo(field.component());
		// The exact annotation and rule the handler matches, so this failure is answered KMS-400003.
		assertThat(only.getConstraintDescriptor().getAnnotation()).isInstanceOf(Pattern.class);
		assertThat(((Pattern) only.getConstraintDescriptor().getAnnotation()).regexp()).isEqualTo(E164_RULE);
	}

	private Object read(PhoneField field, String typed) throws Exception {
		return json.readValue(field.body().formatted(json.writeValueAsString(typed)), field.type());
	}

	private static List<? extends ConstraintViolation<?>> violationsOn(
			Set<? extends ConstraintViolation<?>> violations, PhoneField field) {
		return violations.stream()
				.filter(v -> v.getPropertyPath().toString().equals(field.component()))
				.toList();
	}

	private static Object valueOf(Object request, PhoneField field) throws Exception {
		for (RecordComponent component : field.type().getRecordComponents()) {
			if (component.getName().equals(field.component())) {
				return component.getAccessor().invoke(request);
			}
		}
		throw new IllegalArgumentException("No component " + field);
	}
}
