package org.iskcon.kms.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Everything this application knows about Meta's WhatsApp Cloud API over HTTP, in one class.
 *
 * <p>Kept separate from {@link ChannelAdapter} deliberately. The adapter's job is "send this message
 * to this person on this channel", which is the same sentence for WhatsApp, SMS and email. This
 * class is the part that is Meta's alone — its URL shapes, its JSON, its error envelope — so a
 * second provider, or Meta changing a path, touches one file.
 *
 * <p>Every call carries a temple's own access token rather than a platform one. The token is passed
 * in rather than held, because it belongs to whichever temple this message is being sent for and
 * this class is a singleton shared by all of them.
 */
@Component
public class MetaWhatsAppClient {

	private static final Logger log = LoggerFactory.getLogger(MetaWhatsAppClient.class);
	private static final Duration TIMEOUT = Duration.ofSeconds(15);

	/**
	 * Meta's {@code error_subcode} for creating a template whose name already exists in that language.
	 *
	 * <p>Documented, which is why it is the primary test (T-168): "Creating a template with a name that
	 * already exists for the same language returns error code 100, subcode 2388024, with message
	 * 'Content in This Language Already Exists'."
	 * https://developers.facebook.com/documentation/business-messaging/whatsapp/templates/template-management
	 */
	static final int CONTENT_IN_THIS_LANGUAGE_ALREADY_EXISTS = 2388024;

	/**
	 * STOPGAP until Meta's code for it is confirmed from staging's DEBUG log (T-168). Meta's own
	 * sentence for an existing template, as staging received it on 2026-09-13 for eleven templates:
	 * "There is already English content for this template. You can create a new template and try
	 * again." The language is part of the sentence, so any language is matched.
	 */
	private static final Pattern ALREADY_HAS_CONTENT_FALLBACK =
			Pattern.compile("there is already .+ content for this template", Pattern.CASE_INSENSITIVE);

	/**
	 * STOPGAP, and the only test there is for this answer (T-168): Meta documents no code or subcode
	 * for it on its error codes, template management or categorization pages, so it is matched on
	 * Meta's sentence as staging received it on 2026-09-13 for {@code donation_thank_you} and
	 * {@code wishlist_gift_split}: "The category UTILITY doesn't match the one that's already
	 * associated with this template, MARKETING." Both apostrophe forms are accepted, and the second
	 * captured word is the category Meta holds. Replace with the code once staging's DEBUG log shows
	 * one.
	 */
	private static final Pattern HELD_UNDER_ANOTHER_CATEGORY_FALLBACK = Pattern.compile(
			"category\\s+(\\w+)\\s+does(?:n['’]t| not)\\s+match the one that['’]s already associated with this template,?\\s*(\\w+)",
			Pattern.CASE_INSENSITIVE);

	private final ObjectMapper objectMapper;
	private final String graphBaseUrl;

	public MetaWhatsAppClient(
			ObjectMapper objectMapper,
			@Value("${kms.notifications.whatsapp.graph-url:https://graph.facebook.com/v21.0}") String graphBaseUrl) {
		this.objectMapper = objectMapper;
		this.graphBaseUrl = graphBaseUrl.endsWith("/")
				? graphBaseUrl.substring(0, graphBaseUrl.length() - 1)
				: graphBaseUrl;
	}

	/**
	 * Asks Meta to describe the temple's business phone number, and reads nothing from the answer
	 * but the fact that it came. A read rather than a write: connecting an account must never put a
	 * message in front of anybody.
	 *
	 * <p>This used to be the whole of the Settings Test button too, and that was the defect T-151
	 * fixed. A number Meta will describe is not a number that can deliver — an unapproved template, a
	 * recipient outside a test account's list, a spent messaging limit all leave this answering
	 * perfectly. The Test button now sends a real message through {@link #sendTemplate}.
	 *
	 * @return the number as Meta displays it, for the screen to show back — proof to an
	 *     administrator that they configured the number they meant to
	 */
	public String verifyNumber(String phoneNumberId, String accessToken) {
		HttpResponse<String> response = call(HttpRequest.newBuilder(
						URI.create(graphBaseUrl + "/" + encode(phoneNumberId)
								+ "?fields=display_phone_number,verified_name"))
				.timeout(TIMEOUT)
				.header("Authorization", "Bearer " + accessToken)
				.GET());

		if (response.statusCode() >= 400) {
			throw new WhatsAppCredentialsRejected(readableError(response));
		}
		try {
			JsonNode body = objectMapper.readTree(response.body());
			String number = text(body, "display_phone_number");
			String name = text(body, "verified_name");
			return name == null ? number : name + " (" + number + ")";
		} catch (IOException e) {
			throw new WhatsAppCredentialsRejected("Meta's answer could not be read.", e);
		}
	}

	/**
	 * Sends one approved template to one number.
	 *
	 * <p>A template and not free text, because everything this application sends is started by the
	 * temple rather than by the person receiving it, and Meta only allows free text inside the
	 * twenty-four hours after that person last wrote to the temple. There is no moment in a shift
	 * reminder's life when that is true.
	 *
	 * @param parameters the template's placeholders in order — {@code {{1}}}, {@code {{2}}} — which
	 *     is why {@link NotificationTemplate} declares an order rather than a map
	 * @return Meta's id for the message, which a later delivery callback is keyed on
	 */
	public String sendTemplate(String phoneNumberId, String accessToken, String toPhone,
			String templateName, String languageCode, List<String> parameters) {

		Map<String, Object> body = Map.of(
				"messaging_product", "whatsapp",
				"to", toPhone,
				"type", "template",
				"template", Map.of(
						"name", templateName,
						"language", Map.of("code", languageCode),
						"components", List.of(Map.of(
								"type", "body",
								"parameters", parameters.stream()
										.map(p -> Map.of("type", "text", "text", p))
										.toList()))));

		HttpResponse<String> response = post(
				graphBaseUrl + "/" + encode(phoneNumberId) + "/messages", accessToken, body);
		if (response.statusCode() >= 400) {
			throw new WhatsAppSendFailed(readableError(response));
		}
		try {
			JsonNode messages = objectMapper.readTree(response.body()).path("messages");
			String id = messages.isArray() && !messages.isEmpty() ? text(messages.get(0), "id") : null;
			if (id == null) {
				throw new WhatsAppSendFailed("Meta accepted the message but named no id for it.");
			}
			return id;
		} catch (IOException e) {
			throw new WhatsAppSendFailed("Meta's answer could not be read.", e);
		}
	}

	/**
	 * Registers one message template against a temple's WhatsApp Business Account.
	 *
	 * <p>Idempotent from the caller's point of view: a template that already exists comes back as an
	 * error naming that, which is reported as {@link TemplateOutcome#ALREADY_EXISTS} rather than
	 * thrown, so re-running the sync is safe.
	 *
	 * <p>That also means a template Meta already holds is never re-worded by this call: a changed body
	 * under an existing name comes back "already exists" and Meta keeps the old one. Meta's rules make
	 * a new name the way to change what is registered — see {@link NotificationTemplate#WHATSAPP_TEST}.
	 * Staging confirmed this on 2026-09-13: the second Save got "already exists" for all thirteen
	 * templates it had registered before, and none went back to review.
	 *
	 * <p>Meta being unreachable is thrown, as {@link WhatsAppCredentialsRejected}, never returned: it
	 * is not an answer from Meta, and the caller records it differently.
	 *
	 * @return the outcome, and for a refusal Meta's own sentence (T-159) — for the log and for the
	 *     caller to translate, never to show an administrator as it stands
	 */
	public TemplateSubmission createTemplate(String wabaId, String accessToken, String name,
			String category, String languageCode, String bodyText, List<String> exampleValues) {

		Map<String, Object> component = exampleValues.isEmpty()
				? Map.of("type", "BODY", "text", bodyText)
				: Map.of("type", "BODY", "text", bodyText,
						"example", Map.of("body_text", List.of(exampleValues)));

		HttpResponse<String> response = post(
				graphBaseUrl + "/" + encode(wabaId) + "/message_templates", accessToken,
				Map.of("name", name, "category", category, "language", languageCode,
						"components", List.of(component)));

		return templateOutcome(name, response.statusCode(), response.body());
	}

	/**
	 * Meta's answer to a template registration, sorted into what it means for the temple (T-168).
	 *
	 * <p><strong>Why this is not a text match any more.</strong> T-159 treated a template as already
	 * registered only when Meta's sentence contained "already exists". Meta's real sentence for that
	 * case is "There is already English content for this template…", so on staging's second Save
	 * eleven templates Meta held perfectly well were stored as refused, with advice to press Save
	 * again that could never work. The documented subcode is now the primary test.
	 *
	 * <p>Every non-2xx answer is logged at DEBUG with Meta's raw code, subcode and title, so the
	 * values Meta really sends can be read from a deployed log and the two text fallbacks below
	 * replaced by codes.
	 */
	private TemplateSubmission templateOutcome(String name, int status, String body) {
		if (status < 400) {
			return new TemplateSubmission(TemplateOutcome.SUBMITTED, null);
		}
		JsonNode error = errorNode(body);
		String readable = readableError(status, body);
		log.debug("Meta answered template {} with HTTP {}: error.code={} error.error_subcode={} error_user_title={}",
				name, status, raw(error, "code"), raw(error, "error_subcode"), raw(error, "error_user_title"));

		TemplateSubmission outcome = classify(error, readable);

		if (outcome.outcome() == TemplateOutcome.REFUSED) {
			log.warn("Meta refused template {}: {}", name, readable);
		}
		return outcome;
	}

	/**
	 * The decision itself, from Meta's error object and its readable sentence.
	 *
	 * <p>The order matters. A category mismatch is checked first because it is the more specific
	 * answer about a template Meta already holds, and Meta does not say whether that answer also
	 * carries the already-exists subcode; if it did, checking the subcode first would hide the
	 * mismatch. Then the documented subcode. Then the stopgap text matches, which also keep T-159's
	 * "already exists" phrase so nothing that used to be recognised stops being recognised.
	 */
	private static TemplateSubmission classify(JsonNode error, String readable) {
		Matcher heldAs = HELD_UNDER_ANOTHER_CATEGORY_FALLBACK.matcher(messages(error, readable));
		if (heldAs.find()) {
			return new TemplateSubmission(TemplateOutcome.HELD_UNDER_ANOTHER_CATEGORY, null,
					heldAs.group(2).toUpperCase(Locale.ROOT));
		}
		if (error.path("error_subcode").asInt(0) == CONTENT_IN_THIS_LANGUAGE_ALREADY_EXISTS) {
			return new TemplateSubmission(TemplateOutcome.ALREADY_EXISTS, null);
		}
		// STOPGAP until the code is confirmed from staging: Meta's words, not its code.
		String words = messages(error, readable);
		if (ALREADY_HAS_CONTENT_FALLBACK.matcher(words).find()
				|| words.toLowerCase(Locale.ROOT).contains("already exists")) {
			return new TemplateSubmission(TemplateOutcome.ALREADY_EXISTS, null);
		}
		return new TemplateSubmission(TemplateOutcome.REFUSED, readable);
	}

	/** Every human-readable field Meta may put its sentence in, joined, for the stopgap matches. */
	private static String messages(JsonNode error, String readable) {
		return String.join(" | ", readable,
				String.valueOf(text(error, "error_user_title")), String.valueOf(text(error, "message")));
	}

	/**
	 * What became of a template we asked Meta to register. Approval is Meta's, and is not instant.
	 *
	 * <p>{@link #HELD_UNDER_ANOTHER_CATEGORY} is T-168's: Meta holds a template of that name, but
	 * under a category it chose itself, and refuses to register it under ours. It is neither refused
	 * (the template exists and can be sent) nor submitted (nothing new was registered).
	 */
	public enum TemplateOutcome { SUBMITTED, ALREADY_EXISTS, HELD_UNDER_ANOTHER_CATEGORY, REFUSED }

	/**
	 * A template registration's outcome, with Meta's reason when it refused.
	 *
	 * @param metaReason Meta's {@code error_user_msg} or {@code message}, only for
	 *     {@link TemplateOutcome#REFUSED}; null otherwise
	 * @param heldCategory the category Meta holds the template under, only for
	 *     {@link TemplateOutcome#HELD_UNDER_ANOTHER_CATEGORY}, e.g. {@code MARKETING}; null otherwise
	 */
	public record TemplateSubmission(TemplateOutcome outcome, String metaReason, String heldCategory) {

		public TemplateSubmission(TemplateOutcome outcome, String metaReason) {
			this(outcome, metaReason, null);
		}
	}

	// ---------------------------------------------------------------------

	private HttpResponse<String> post(String url, String accessToken, Map<String, Object> body) {
		try {
			return call(HttpRequest.newBuilder(URI.create(url))
					.timeout(TIMEOUT)
					.header("Authorization", "Bearer " + accessToken)
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))));
		} catch (IOException e) {
			throw new WhatsAppSendFailed("Could not write the request to Meta.", e);
		}
	}

	private HttpResponse<String> call(HttpRequest.Builder request) {
		try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
			return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
		} catch (IOException e) {
			throw new WhatsAppCredentialsRejected("Could not reach Meta just now.", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new WhatsAppCredentialsRejected("Could not reach Meta just now.", e);
		}
	}

	/**
	 * Meta's error envelope, reduced to the sentence worth showing someone.
	 *
	 * <p>Their {@code error.message} is written for a developer but it is specific, and specific
	 * beats "something went wrong" when an administrator is looking at a token they pasted a minute
	 * ago. Nothing here is secret: it is Meta describing our own request back to us.
	 */
	private String readableError(HttpResponse<String> response) {
		return readableError(response.statusCode(), response.body());
	}

	private String readableError(int status, String body) {
		JsonNode error = errorNode(body);
		String message = text(error, "error_user_msg");
		if (message == null) {
			message = text(error, "message");
		}
		return message == null ? "Meta answered HTTP " + status : message;
	}

	/** Meta's {@code error} object, or a missing node when the body is not Meta's JSON at all. */
	private JsonNode errorNode(String body) {
		try {
			return body == null ? MissingNode.getInstance() : objectMapper.readTree(body).path("error");
		} catch (IOException e) {
			return MissingNode.getInstance();
		}
	}

	/** A field's raw value for the log, or "absent" — so a missing subcode reads as missing, not 0. */
	private static String raw(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value == null || value.isNull() ? "absent" : value.asText();
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value == null || value.isNull() ? null : value.asText();
	}

	private static String encode(String segment) {
		return URLEncoder.encode(segment, StandardCharsets.UTF_8);
	}

	/** Meta would not accept these credentials, with a reason fit to show an administrator. */
	public static class WhatsAppCredentialsRejected extends RuntimeException {
		public WhatsAppCredentialsRejected(String message) {
			super(message);
		}

		public WhatsAppCredentialsRejected(String message, Throwable cause) {
			super(message, cause);
		}
	}

	/** One message did not leave. The cascade falls to SMS; nothing about the temple is wrong. */
	public static class WhatsAppSendFailed extends RuntimeException {
		public WhatsAppSendFailed(String message) {
			super(message);
		}

		public WhatsAppSendFailed(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
