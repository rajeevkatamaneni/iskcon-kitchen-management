package org.iskcon.kms.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
	 * but the fact that it came. A read rather than a write: pressing Test must never put a message
	 * in front of anybody.
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
	 */
	public TemplateOutcome createTemplate(String wabaId, String accessToken, String name,
			String category, String languageCode, String bodyText, List<String> exampleValues) {

		Map<String, Object> component = exampleValues.isEmpty()
				? Map.of("type", "BODY", "text", bodyText)
				: Map.of("type", "BODY", "text", bodyText,
						"example", Map.of("body_text", List.of(exampleValues)));

		HttpResponse<String> response = post(
				graphBaseUrl + "/" + encode(wabaId) + "/message_templates", accessToken,
				Map.of("name", name, "category", category, "language", languageCode,
						"components", List.of(component)));

		if (response.statusCode() < 400) {
			return TemplateOutcome.SUBMITTED;
		}
		String error = readableError(response);
		if (error.toLowerCase().contains("already exists")) {
			return TemplateOutcome.ALREADY_EXISTS;
		}
		log.warn("Meta refused template {}: {}", name, error);
		return TemplateOutcome.REFUSED;
	}

	/** What became of a template we asked Meta to register. Approval is Meta's, and is not instant. */
	public enum TemplateOutcome { SUBMITTED, ALREADY_EXISTS, REFUSED }

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
		try {
			JsonNode error = objectMapper.readTree(response.body()).path("error");
			String message = text(error, "error_user_msg");
			if (message == null) {
				message = text(error, "message");
			}
			return message == null ? "Meta answered HTTP " + response.statusCode() : message;
		} catch (IOException e) {
			return "Meta answered HTTP " + response.statusCode();
		}
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
