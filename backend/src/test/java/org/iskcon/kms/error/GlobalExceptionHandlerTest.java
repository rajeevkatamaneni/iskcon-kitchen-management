package org.iskcon.kms.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.iskcon.kms.ingredient.Unit;
import org.iskcon.kms.staff.JobTitle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * What a caller is told when the body or the address contains a value we cannot read (T-105).
 *
 * <h2>The defect this is the guard for</h2>
 *
 * <p>Posting a purchase order line with {@code "unit": "EACH"} — not a member of {@link Unit}, and
 * an ordinary typo — used to be answered KMS-500001 and an HTTP 500, "Something went wrong at our
 * end". That much was fixed in {@code bed58c8}: it is a KMS-400001 and a 400 now, which is true.
 *
 * <p><strong>What was still wrong, and is what these tests hold, is that the answer named nothing.
 * </strong> An unparseable body skips Bean Validation entirely — Jackson fails before the validator
 * is ever reached — so the request that is <em>most</em> obviously the caller's own mistake was
 * given the <em>least</em> useful answer in the product: the same code a failed constraint gets, but
 * with an empty field list, while a constraint failure an inch away on the same endpoint arrives
 * naming the box. "Some of the information entered isn't valid. Check the highlighted fields" is not
 * something a person can act on when nothing is highlighted.
 *
 * <h2>Why a unit test and not only an integration one</h2>
 *
 * <p>The exceptions here are produced by running a real {@link ObjectMapper} over real JSON rather
 * than being hand-built, so the {@code getPath()} and target type these assertions read are exactly
 * the ones Jackson hands the application at runtime — a fabricated exception would prove only that
 * the test author and the handler agree with each other. Everything past that point is a pure
 * function of the exception, needs no database, and so is tested without one.
 * {@code ErrorResponseIT} carries the same case end to end over real HTTP, which is what proves the
 * handler is wired up at all.
 *
 * <h2>The other half: what must <em>not</em> appear</h2>
 *
 * <p>Jackson's own sentence is "Cannot deserialize value of type `Unit` from String "EACH"". Naming
 * the field and its values is the fix; pasting that is the trap, and {@link #nothingFromInsideTheMachineReachesTheCaller()}
 * is the test that keeps the two apart.
 */
class GlobalExceptionHandlerTest {

	private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

	private final ObjectMapper mapper = new ObjectMapper();

	// ---------------------------------------------------------------------------------------
	// A body Jackson cannot read.
	// ---------------------------------------------------------------------------------------

	@Test
	@DisplayName("a unit that is not one names the field and lists what is allowed")
	void anUnknownEnumValueNamesTheFieldAndItsValues() {
		ErrorResponse body = unreadable("{\"unit\":\"EACH\",\"quantity\":5}", OrderLine.class);

		assertThat(body.code()).isEqualTo("KMS-400001");
		assertThat(body.fieldErrors())
				.as("the whole point of the task: the caller must be told which box and which words")
				.containsExactly(new ErrorResponse.FieldError(
						"unit", "Choose one of KG, GM, L, ML, PIECES."));
	}

	@Test
	@DisplayName("the answer is a 400, not an incident")
	void theStatusIsAClientError() {
		ResponseEntity<ErrorResponse> response = handleUnreadable("{\"unit\":\"EACH\"}", OrderLine.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().code()).isEqualTo(ErrorCode.VALIDATION_FAILED.reference());
	}

	@Test
	@DisplayName("a bad value inside a list of lines names the line it is on")
	void aNestedFieldIsNamedWithItsPosition() {
		// The real shape of the reported defect: a purchase order posts its lines as an array, and
		// "the unit is wrong" is useless to somebody looking at eleven of them.
		ErrorResponse body = unreadable(
				"{\"lines\":[{\"unit\":\"KG\"},{\"unit\":\"EACH\"}]}", Order.class);

		assertThat(body.fieldErrors()).hasSize(1);
		assertThat(body.fieldErrors().get(0).field()).isEqualTo("lines[1].unit");
	}

	@Test
	@DisplayName("a value of the wrong kind altogether is named, without guessing at its format")
	void aNonEnumMismatchIsStillNamed() {
		ErrorResponse body = unreadable("{\"quantity\":\"a handful\"}", OrderLine.class);

		assertThat(body.fieldErrors())
				.containsExactly(new ErrorResponse.FieldError(
						"quantity", "That isn't a value we can use here."));
	}

	@Test
	@DisplayName("an enum too long to read is named but not listed")
	void aVeryLongListOfValuesIsNotPrintedAtAll() {
		// JobTitle has seventeen members. Printing all of them next to the box is a wall, not help,
		// so the field is named — which was the actual defect — and the list is left to the docs.
		ErrorResponse body = unreadable("{\"jobTitle\":\"CHIEF\"}", StaffRecord.class);

		assertThat(body.fieldErrors())
				.containsExactly(new ErrorResponse.FieldError(
						"jobTitle", "That isn't a value we can use here."));
		assertThat(body.fieldErrors().get(0).message())
				.as("no wall of constants")
				.doesNotContain("PRASADAM_SERVER");
	}

	@Test
	@DisplayName("a body that is not JSON at all names no field rather than inventing one")
	void anUnparseableBodyNamesNothing() {
		// There is no path in a truncated body — Jackson never got as far as a property — and a
		// field error here would point somebody at a box that is perfectly fine. The code and the
		// status are unchanged; only the list is empty, and honestly so.
		ErrorResponse body = unreadable("{\"unit\":", OrderLine.class);

		assertThat(body.code()).isEqualTo("KMS-400001");
		assertThat(body.fieldErrors()).isEmpty();
	}

	@Test
	@DisplayName("nothing from inside the machine reaches the caller")
	void nothingFromInsideTheMachineReachesTheCaller() {
		// Jackson's own message here is: Cannot deserialize value of type
		// `org.iskcon.kms.ingredient.Unit` from String "EACH": not one of the values accepted for
		// Enum class: [KG, GM, L, ML, PIECES]
		//
		// Every value in it is useful and almost none of it may be shown. This is the line between
		// naming the field — which is the caller's own vocabulary — and pasting the exception.
		ErrorResponse body = unreadable("{\"unit\":\"EACH\"}", OrderLine.class);
		String everythingSaid = body.code() + " " + body.message() + " " + body.action() + " "
				+ body.fieldErrors();

		assertThat(everythingSaid)
				.doesNotContain("deserialize")
				.doesNotContain("Enum")
				.doesNotContain("org.iskcon")
				.doesNotContain("com.fasterxml")
				.doesNotContain("java.lang")
				.doesNotContain("Exception")
				.doesNotContain("OrderLine");

		assertThat(everythingSaid.toLowerCase(Locale.ROOT))
				.as("the same words ErrorCodeTest bans from a KMS-nnnnnn sentence, an inch away "
						+ "from one on the same screen")
				.doesNotContain("exception")
				.doesNotContain("payload")
				.doesNotContain("server")
				.doesNotContain("endpoint")
				.doesNotContain("null");
	}

	@Test
	@DisplayName("the generated sentence is written the way every other field error is")
	void theGeneratedSentenceReadsLikeTheProduct() {
		// FieldErrorMessageTest holds every hand-written constraint message to a capital, a verb and
		// a full stop. These two are generated rather than written, so that test cannot see them and
		// this one has to.
		List<String> generated = List.of(
				unreadable("{\"unit\":\"EACH\"}", OrderLine.class).fieldErrors().get(0).message(),
				unreadable("{\"quantity\":\"a handful\"}", OrderLine.class).fieldErrors().get(0).message());

		generated.forEach(message -> {
			assertThat(message).startsWith(message.substring(0, 1).toUpperCase(Locale.ROOT));
			assertThat(message).endsWith(".");
			assertThat(message).doesNotContain("!");
		});
	}

	// ---------------------------------------------------------------------------------------
	// A value in the address that cannot be what it claims.
	// ---------------------------------------------------------------------------------------

	@Test
	@DisplayName("an address that names no resource stays a 404, and now says which segment")
	void anUnusableAddressValueNamesTheParameter() {
		ResponseEntity<ErrorResponse> response = handler.handleUnusableRequestValue(
				mismatch("EACH", Unit.class, "unit", "anAddressSegment"),
				new MockHttpServletRequest("GET", "/api/v1/stock/EACH"));

		assertThat(response.getStatusCode())
				.as("nothing exists at that address and nothing could — 404 is the truth")
				.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().code()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND.reference());
		assertThat(response.getBody().fieldErrors())
				.containsExactly(new ErrorResponse.FieldError(
						"unit", "Choose one of KG, GM, L, ML, PIECES."));
	}

	@Test
	@DisplayName("an address value with no target type is named without advice")
	void anAddressValueWithNoTypeIsStillNamed() {
		ResponseEntity<ErrorResponse> response = handler.handleUnusableRequestValue(
				mismatch("undefined", null, "invoiceId", "anAddressSegment"),
				new MockHttpServletRequest("GET", "/api/v1/vendor-invoices/undefined/payments"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().fieldErrors())
				.containsExactly(new ErrorResponse.FieldError(
						"invoiceId", "That isn't a value we can use here."));
	}

	@Test
	@DisplayName("a misspelt filter value is a bad field, not a missing order")
	void aMisspeltFilterValueIsAValidationFailureNotAMissingResource() {
		// The half of this exception that was answered wrongly. /orders?status=SNET names a real
		// collection and one misspelt word: the resource is fine and the field is not. Telling
		// somebody "We couldn't find what you were looking for" sends them hunting for an order
		// that exists — the same defect T-105 is about, one layer along.
		ResponseEntity<ErrorResponse> response = handler.handleUnusableRequestValue(
				mismatch("SNET", Unit.class, "unit", "aFilterValue"),
				new MockHttpServletRequest("GET", "/api/v1/orders"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().code()).isEqualTo(ErrorCode.VALIDATION_FAILED.reference());
		assertThat(response.getBody().fieldErrors())
				.as("the same answer a failed constraint on the same endpoint would give")
				.containsExactly(new ErrorResponse.FieldError(
						"unit", "Choose one of KG, GM, L, ML, PIECES."));
	}

	@Test
	@DisplayName("a parameter we cannot see at all takes the answer that is never misleading")
	void aParameterWithNoDeclarationTakesTheValidationFailure() {
		// The fallback, stated so it is a decision and not an accident: a 400 naming the parameter
		// asserts nothing about what exists, while a 404 guessed at in the dark tells somebody
		// their order is missing when it is not.
		ResponseEntity<ErrorResponse> response = handler.handleUnusableRequestValue(
				new MethodArgumentTypeMismatchException(
						"EACH", Unit.class, "unit", null, new IllegalArgumentException()),
				new MockHttpServletRequest("GET", "/api/v1/orders"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().code()).isEqualTo(ErrorCode.VALIDATION_FAILED.reference());
	}

	// ---------------------------------------------------------------------------------------
	// Fixtures.
	// ---------------------------------------------------------------------------------------

	/**
	 * Runs the mapper for real and hands the handler whatever it threw, wrapped the way Spring's
	 * message converter wraps it.
	 */
	private ResponseEntity<ErrorResponse> handleUnreadable(String json, Class<?> target) {
		Exception thrown = null;
		try {
			mapper.readValue(json, target);
		}
		catch (Exception e) {
			thrown = e;
		}
		assertThat(thrown)
				.as("this fixture only means anything if the mapper actually refused %s", json)
				.isNotNull();

		return handler.handleUnreadableBody(
				new HttpMessageNotReadableException("JSON parse error", thrown, bodyOf(json)),
				new MockHttpServletRequest("POST", "/api/v1/purchase-orders"));
	}

	private ErrorResponse unreadable(String json, Class<?> target) {
		ErrorResponse body = handleUnreadable(json, target).getBody();
		assertThat(body).isNotNull();
		return body;
	}

	private static HttpInputMessage bodyOf(String json) {
		return new HttpInputMessage() {

			@Override
			public InputStream getBody() {
				return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
			}

			@Override
			public HttpHeaders getHeaders() {
				return new HttpHeaders();
			}
		};
	}

	/**
	 * The exception Spring raises, pointed at a parameter declared the way a real controller would
	 * declare it.
	 *
	 * <p>The annotation on the fixture method is the whole point rather than decoration: the handler
	 * decides 404-or-400 by asking the parameter whether it is a {@code @PathVariable}, so a fixture
	 * that carried no annotation would test the fallback every time and quietly prove nothing about
	 * the split.
	 */
	private static MethodArgumentTypeMismatchException mismatch(
			Object value, Class<?> requiredType, String name, String declaredBy) {

		return new MethodArgumentTypeMismatchException(
				value, requiredType, name, aMethodParameter(declaredBy), new IllegalArgumentException());
	}

	private static MethodParameter aMethodParameter(String fixtureMethod) {
		Method method;
		try {
			method = GlobalExceptionHandlerTest.class.getDeclaredMethod(fixtureMethod, Unit.class);
		}
		catch (NoSuchMethodException e) {
			throw new IllegalStateException("the fixture method " + fixtureMethod + " was renamed", e);
		}
		return new MethodParameter(method, 0);
	}

	/** Part of the address: {@code /api/v1/stock/{unit}}. */
	@SuppressWarnings("unused")
	private static void anAddressSegment(@PathVariable Unit unit) {
	}

	/** Not part of the address: {@code /api/v1/orders?unit=…}. */
	@SuppressWarnings("unused")
	private static void aFilterValue(@RequestParam Unit unit) {
	}

	/**
	 * Stand-ins for the real request bodies, using the product's own {@link Unit} and
	 * {@link JobTitle} so a change to either shows up here.
	 *
	 * <p>Deliberately not the real {@code CreatePurchaseOrderRequest}: that record is another
	 * builder's file this wave, and what is being tested is a property of the exception handler
	 * rather than of any one endpoint's shape.
	 */
	private record OrderLine(Unit unit, int quantity) {
	}

	private record Order(List<OrderLine> lines) {
	}

	private record StaffRecord(JobTitle jobTitle) {
	}
}
