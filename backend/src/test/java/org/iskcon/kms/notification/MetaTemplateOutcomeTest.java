package org.iskcon.kms.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.iskcon.kms.notification.MetaWhatsAppClient.TemplateOutcome;
import org.iskcon.kms.notification.MetaWhatsAppClient.TemplateSubmission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Every answer Meta gives to a template registration, run through the real client over real HTTP, and
 * what each one is taken to mean (T-168).
 *
 * <p><strong>Why this exists.</strong> On 2026-09-13 staging's second Save stored thirteen templates as
 * refused that Meta held perfectly well. The client recognised an existing template only by the words
 * "already exists", and Meta's sentence for it is "There is already English content for this
 * template". Nothing ran a real Meta error body through the classifier, so nothing noticed.
 *
 * <p><strong>How it is exercised.</strong> A JDK {@link HttpServer} on 127.0.0.1 answers with the status
 * and body each test sets, and the production {@link MetaWhatsAppClient} is pointed at it. So what is
 * tested is the request going out, Meta's JSON being read, and the decision, with nothing mocked. The
 * same approach as {@code GeocodingIT}.
 *
 * <p><strong>Where each body comes from</strong>, because a made-up error body proves only that the
 * code matches what the test's author imagined:
 * <ul>
 *   <li>The envelope's shape ({@code message}, {@code type}, {@code code}, {@code error_subcode},
 *       {@code error_user_title}, {@code error_user_msg}, {@code fbtrace_id}) is Meta's Graph API error
 *       object, which {@code readableError} already parsed before this task.</li>
 *   <li>Code 100 and subcode 2388024 with the title "Content in This Language Already Exists": Meta's
 *       template management page,
 *       https://developers.facebook.com/documentation/business-messaging/whatsapp/templates/template-management</li>
 *   <li>Subcodes 2388293 and 2388299 and their sentences: Meta's error codes page,
 *       https://developers.facebook.com/documentation/business-messaging/whatsapp/support/error-codes ,
 *       whose descriptions match staging's refusals word for word. Their titles are the names that page
 *       gives the codes; staging did not record the titles.</li>
 *   <li>Every {@code error_user_msg} marked "staging" is Meta's sentence as staging logged it, on
 *       2026-09-12 or 2026-09-13.</li>
 *   <li><strong>The category mismatch carries no subcode or title here on purpose.</strong> Meta
 *       documents none for it, and staging did not record one, so inventing one would make this test
 *       agree with a guess. Code 100 is what the template management page says an invalid category
 *       returns.</li>
 * </ul>
 */
class MetaTemplateOutcomeTest {

	/** Meta creating the template. Shape from the template management page. */
	private static final String CREATED = """
			{"id":"1234567890123456","status":"PENDING","category":"UTILITY"}
			""";

	/** Documented code, subcode and title; the sentence staging received for eleven templates. */
	private static final String ALREADY_EXISTS = """
			{"error":{"message":"Invalid parameter","type":"OAuthException","code":100,
			"error_subcode":2388024,"is_transient":false,
			"error_user_title":"Content in This Language Already Exists",
			"error_user_msg":"There is already English content for this template. You can create a new template and try again.",
			"fbtrace_id":"AQc1T168aaaa"}}
			""";

	/** Staging's sentence, staging's category names. No subcode or title: none is documented. */
	private static final String HELD_AS_MARKETING = """
			{"error":{"message":"Invalid parameter","type":"OAuthException","code":100,"is_transient":false,
			"error_user_msg":"The category UTILITY doesn't match the one that's already associated with this template, MARKETING.",
			"fbtrace_id":"AQc1T168bbbb"}}
			""";

	private static final String TOO_MANY_VARIABLES = """
			{"error":{"message":"Invalid parameter","type":"OAuthException","code":100,
			"error_subcode":2388293,"is_transient":false,
			"error_user_title":"Parameters words ratio exceeds limit",
			"error_user_msg":"This template has too many variables for its length. Reduce the number of variables or increase the message length.",
			"fbtrace_id":"AQc1T168cccc"}}
			""";

	private static final String VARIABLE_AT_START_OR_END = """
			{"error":{"message":"Invalid parameter","type":"OAuthException","code":100,
			"error_subcode":2388299,"is_transient":false,
			"error_user_title":"Leading or trailing parameters not allowed",
			"error_user_msg":"Variables can't be at the start or end of the template.",
			"fbtrace_id":"AQc1T168dddd"}}
			""";

	/** Staging's sentence for temple_communication. No subcode: Meta's page does not map this sentence to one. */
	private static final String NEWLINES_OR_ONLY_PARAMETERS = """
			{"error":{"message":"Invalid parameter","type":"OAuthException","code":100,"is_transient":false,
			"error_user_msg":"The message body can't have more than two consecutive newline characters, only have parameters, or have more than 10 emojis.",
			"fbtrace_id":"AQc1T168eeee"}}
			""";

	private HttpServer server;
	private final AtomicInteger status = new AtomicInteger(200);
	private final AtomicReference<String> body = new AtomicReference<>(CREATED);
	private final AtomicReference<String> askedFor = new AtomicReference<>();
	private MetaWhatsAppClient client;

	@BeforeEach
	void startMeta() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			askedFor.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " "
					+ new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			byte[] out = body.get().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(status.get(), out.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(out);
			}
		});
		server.start();
		client = new MetaWhatsAppClient(new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort());
	}

	@AfterEach
	void stopMeta() {
		server.stop(0);
	}

	private TemplateSubmission metaAnswers(int httpStatus, String answer) {
		status.set(httpStatus);
		body.set(answer);
		return client.createTemplate("waba-t168", "token-t168", "leave_approved", "UTILITY", "en",
				"Your leave from {{1}} has been approved. Thank you for letting us know.", List.of("12 Sep"));
	}

	@Test
	@DisplayName("Meta creating the template is SUBMITTED, and the request went where Meta expects it")
	void created() {
		assertThat(metaAnswers(200, CREATED))
				.isEqualTo(new TemplateSubmission(TemplateOutcome.SUBMITTED, null, null));
		assertThat(askedFor.get())
				.startsWith("POST /waba-t168/message_templates ")
				.contains("\"name\":\"leave_approved\"")
				.contains("\"category\":\"UTILITY\"");
	}

	@Test
	@DisplayName("Meta's real answer for a template it already holds is ALREADY_EXISTS, not a refusal")
	void alreadyExists() {
		assertThat(metaAnswers(400, ALREADY_EXISTS))
				.isEqualTo(new TemplateSubmission(TemplateOutcome.ALREADY_EXISTS, null, null));
	}

	@Test
	@DisplayName("the documented subcode alone is enough, whatever words Meta uses")
	void alreadyExistsByCodeAlone() {
		String reworded = ALREADY_EXISTS
				.replace("There is already English content for this template. You can create a new template and try again.",
						"Ya existe contenido en español para esta plantilla.")
				.replace("Content in This Language Already Exists", "Contenido duplicado");
		assertThat(reworded).doesNotContainIgnoringCase("already");

		assertThat(metaAnswers(400, reworded).outcome()).isEqualTo(TemplateOutcome.ALREADY_EXISTS);
	}

	@Test
	@DisplayName("stopgap: Meta's sentence alone, with no subcode, is still ALREADY_EXISTS")
	void alreadyExistsByTheFallbackAlone() {
		String wordsOnly = ALREADY_EXISTS
				.replace("\"error_subcode\":2388024,", "")
				.replace("\"error_user_title\":\"Content in This Language Already Exists\",", "");
		assertThat(wordsOnly).doesNotContain("2388024").doesNotContain("error_user_title");

		assertThat(metaAnswers(400, wordsOnly).outcome()).isEqualTo(TemplateOutcome.ALREADY_EXISTS);
	}

	@Test
	@DisplayName("a template Meta holds as MARKETING is its own outcome, naming the category Meta holds")
	void heldAsMarketing() {
		assertThat(metaAnswers(400, HELD_AS_MARKETING))
				.isEqualTo(new TemplateSubmission(TemplateOutcome.HELD_UNDER_ANOTHER_CATEGORY, null, "MARKETING"));

		// Meta's pages use a typographic apostrophe; its API used a straight one on staging. Either.
		assertThat(metaAnswers(400, HELD_AS_MARKETING.replace("'", "’")))
				.isEqualTo(new TemplateSubmission(TemplateOutcome.HELD_UNDER_ANOTHER_CATEGORY, null, "MARKETING"));
	}

	@Test
	@DisplayName("the three rule refusals staging saw stay REFUSED, carrying Meta's sentence for the log")
	void ruleRefusals() {
		assertThat(metaAnswers(400, TOO_MANY_VARIABLES)).isEqualTo(new TemplateSubmission(TemplateOutcome.REFUSED,
				"This template has too many variables for its length. Reduce the number of variables or increase the message length.",
				null));
		assertThat(metaAnswers(400, VARIABLE_AT_START_OR_END)).isEqualTo(new TemplateSubmission(
				TemplateOutcome.REFUSED, "Variables can't be at the start or end of the template.", null));
		assertThat(metaAnswers(400, NEWLINES_OR_ONLY_PARAMETERS).outcome()).isEqualTo(TemplateOutcome.REFUSED);
	}

	/**
	 * The stopgap must not be greedy. A name whose English content is still being deleted is a real
	 * refusal — nothing is held, and nothing will be for weeks — and its sentence also talks about
	 * existing English content. Sentence as HighLevel's support page quotes it,
	 * https://help.gohighlevel.com/support/solutions/articles/155000006234 ; Meta does not document it.
	 */
	@Test
	@DisplayName("content still being deleted is REFUSED, although its sentence mentions existing content")
	void pendingDeletionIsARefusal() {
		String pendingDeletion = """
				{"error":{"message":"Invalid parameter","type":"OAuthException","code":100,
				"error_user_msg":"New English (US) content can't be added while the existing English (US) content is being deleted. Try again in 4 weeks or consider creating a new message template.",
				"fbtrace_id":"AQc1T168ffff"}}
				""";
		assertThat(metaAnswers(400, pendingDeletion).outcome()).isEqualTo(TemplateOutcome.REFUSED);
	}

	@Test
	@DisplayName("a refusal we have never seen is REFUSED with Meta's message, never taken as success")
	void unknownRefusal() {
		assertThat(metaAnswers(400, """
				{"error":{"message":"(#100) Invalid parameter","type":"OAuthException","code":100,"fbtrace_id":"AQc1T168gggg"}}
				""")).isEqualTo(new TemplateSubmission(TemplateOutcome.REFUSED, "(#100) Invalid parameter", null));
	}

	@Test
	@DisplayName("an answer that is not Meta's JSON at all is REFUSED with the status, not a crash")
	void notMetasJson() {
		assertThat(metaAnswers(502, "<html><body>Bad Gateway</body></html>"))
				.isEqualTo(new TemplateSubmission(TemplateOutcome.REFUSED, "Meta answered HTTP 502", null));
	}

	@Test
	@DisplayName("Meta unreachable is thrown, not returned as any outcome")
	void unreachable() throws Exception {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0)) {
			closedPort = socket.getLocalPort();
		}
		MetaWhatsAppClient nowhere = new MetaWhatsAppClient(new ObjectMapper(), "http://127.0.0.1:" + closedPort);

		assertThatThrownBy(() -> nowhere.createTemplate("waba-t168", "token-t168", "leave_approved", "UTILITY",
				"en", "Your leave from {{1}} has been approved.", List.of("12 Sep")))
				.isInstanceOf(MetaWhatsAppClient.WhatsAppCredentialsRejected.class);
	}

	/**
	 * The DEBUG line is how the stopgaps get replaced: after release, staging's log shows the code and
	 * subcode Meta really sent for each answer. So its content is pinned, including that a missing
	 * subcode reads "absent" rather than a 0 somebody might take for a real value.
	 */
	@Test
	@DisplayName("every non-2xx answer logs Meta's raw code, subcode and title at DEBUG")
	void rawValuesAreLoggedAtDebug() {
		ch.qos.logback.classic.Logger logger =
				(ch.qos.logback.classic.Logger) LoggerFactory.getLogger(MetaWhatsAppClient.class);
		Level before = logger.getLevel();
		ListAppender<ILoggingEvent> captured = new ListAppender<>();
		captured.start();
		logger.addAppender(captured);
		logger.setLevel(Level.DEBUG);
		try {
			metaAnswers(400, ALREADY_EXISTS);
			metaAnswers(400, HELD_AS_MARKETING);
			metaAnswers(200, CREATED);
		} finally {
			logger.detachAppender(captured);
			logger.setLevel(before);
		}

		List<String> debug = captured.list.stream()
				.filter(e -> e.getLevel() == Level.DEBUG)
				.map(ILoggingEvent::getFormattedMessage)
				.toList();
		assertThat(debug).containsExactly(
				"Meta answered template leave_approved with HTTP 400: error.code=100 error.error_subcode=2388024 "
						+ "error_user_title=Content in This Language Already Exists",
				"Meta answered template leave_approved with HTTP 400: error.code=100 error.error_subcode=absent "
						+ "error_user_title=absent");
	}
}
