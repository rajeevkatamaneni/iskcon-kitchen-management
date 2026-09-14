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
import java.util.Optional;
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
		return sendTemplate(phoneNumberId, accessToken, toPhone, templateName, languageCode, parameters, null);
	}

	/**
	 * Sends one approved template whose header carries a document (T-200), or no header when
	 * {@code header} is null, which is exactly {@link #sendTemplate(String, String, String, String, String, List)}.
	 *
	 * <p>The header goes first among the components and names the document by the media id
	 * {@link #uploadMedia} returned. Meta's media object takes "Either {@code id} or {@code link}"; the id
	 * is used, because a link would have to be a public address to a temple's purchase order, and this
	 * application serves documents only behind sign-in. The file name is what the vendor's phone shows
	 * under the PDF.
	 */
	public String sendTemplate(String phoneNumberId, String accessToken, String toPhone,
			String templateName, String languageCode, List<String> parameters, HeaderDocument header) {

		List<Map<String, Object>> components = new java.util.ArrayList<>();
		if (header != null) {
			components.add(Map.of(
					"type", "header",
					"parameters", List.of(Map.of(
							"type", "document",
							"document", Map.of("id", header.mediaId(), "filename", header.filename())))));
		}
		components.add(Map.of(
				"type", "body",
				"parameters", parameters.stream()
						.map(p -> Map.of("type", "text", "text", p))
						.toList()));

		Map<String, Object> body = Map.of(
				"messaging_product", "whatsapp",
				"to", toPhone,
				"type", "template",
				"template", Map.of(
						"name", templateName,
						"language", Map.of("code", languageCode),
						"components", components));

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
		return createTemplate(wabaId, accessToken, name, category, languageCode, bodyText, exampleValues, null);
	}

	/**
	 * Registers a template with a header (T-200); with {@code header} null it is exactly the seven-argument
	 * form above.
	 *
	 * <p>The header component is Meta's, from its template components guide: {@code {"type": "HEADER",
	 * "format": "DOCUMENT", "example": {"header_handle": ["4::YX..."]}}}. The handle is what
	 * {@link #uploadTemplateSample} returns, and Meta's reviewer sees that sample.
	 * https://developers.facebook.com/documentation/business-messaging/whatsapp/templates/components
	 */
	public TemplateSubmission createTemplate(String wabaId, String accessToken, String name,
			String category, String languageCode, String bodyText, List<String> exampleValues,
			TemplateHeader header) {

		HttpResponse<String> response = post(
				graphBaseUrl + "/" + encode(wabaId) + "/message_templates", accessToken,
				Map.of("name", name, "category", category, "language", languageCode,
						"components", components(bodyText, exampleValues, header)));

		return templateOutcome(name, response.statusCode(), response.body());
	}

	/**
	 * The components of a registration or an edit: the header first when there is one, then the body.
	 * One method so a create and an edit cannot describe the same template in two shapes.
	 */
	private static List<Map<String, Object>> components(String bodyText, List<String> exampleValues,
			TemplateHeader header) {
		List<Map<String, Object>> components = new java.util.ArrayList<>();
		if (header != null) {
			components.add(Map.of("type", "HEADER", "format", header.format(),
					"example", Map.of("header_handle", List.of(header.exampleHandle()))));
		}
		components.add(exampleValues.isEmpty()
				? Map.of("type", "BODY", "text", bodyText)
				: Map.of("type", "BODY", "text", bodyText,
						"example", Map.of("body_text", List.of(exampleValues))));
		return components;
	}

	/**
	 * A template header as it is registered: Meta's format, e.g. {@code DOCUMENT}, and the handle of the
	 * sample Meta's reviewer is shown.
	 */
	public record TemplateHeader(String format, String exampleHandle) {
	}

	/** A document to send in a template's header: Meta's media id for it, and the name the phone shows. */
	public record HeaderDocument(String mediaId, String filename) {
	}

	/**
	 * Uploads a sample file through Meta's Resumable Upload API and returns its handle, for the example a
	 * template with a media header must be registered with (T-200).
	 *
	 * <p><strong>Meta's steps, from its Resumable Upload API guide</strong>
	 * (https://developers.facebook.com/docs/graph-api/guides/upload):
	 * <ol>
	 *   <li>{@code POST /<APP_ID>/uploads} with {@code file_name}, {@code file_length} and {@code file_type},
	 *       which answers {@code {"id": "upload:<UPLOAD_SESSION_ID>"}}.</li>
	 *   <li>{@code POST /upload:<UPLOAD_SESSION_ID>} with the headers {@code Authorization: OAuth
	 *       <USER_ACCESS_TOKEN>} and {@code file_offset: 0} and the file's bytes as the body, which answers
	 *       {@code {"h": "<UPLOADED_FILE_HANDLE>"}}.</li>
	 * </ol>
	 *
	 * <p><strong>Two things this does that the guide's example does not.</strong> The token goes in the
	 * {@code Authorization} header on the first request too, rather than as the {@code access_token} query
	 * parameter the guide shows, so it can never land in an access log as part of a URL; the Graph API
	 * reads either. And the session id is appended to the address exactly as Meta sent it, not encoded,
	 * because Meta's own id carries its colon and may carry a query of its own, and encoding either would
	 * address a different session. It is checked to start with {@code upload:} and to hold no slash or
	 * whitespace first, so an answer that is not a session id is never followed anywhere.
	 *
	 * <p><strong>Unknown, and deliberately not tried by the builder:</strong> the guide asks for "A User
	 * access token", and a temple stores a System User token. Whether Meta accepts that here is verified
	 * on staging, not assumed. A refusal comes back as {@link WhatsAppSendFailed} carrying Meta's sentence,
	 * for the log and for the caller to translate.
	 *
	 * @throws WhatsAppCredentialsRejected when Meta cannot be reached
	 * @throws WhatsAppSendFailed when Meta answers with an error, or with no session id or handle
	 */
	public String uploadTemplateSample(String appId, String accessToken, byte[] content, String fileName,
			String fileType) {
		HttpResponse<String> started = call(HttpRequest.newBuilder(
						URI.create(graphBaseUrl + "/" + encode(appId) + "/uploads?file_name=" + encode(fileName)
								+ "&file_length=" + content.length + "&file_type=" + encode(fileType)))
				.timeout(TIMEOUT)
				.header("Authorization", "OAuth " + accessToken)
				.POST(HttpRequest.BodyPublishers.noBody()));
		if (started.statusCode() >= 400) {
			String readable = readableError(started);
			log.warn("Meta would not start an upload session for a template sample: {}", readable);
			throw new WhatsAppSendFailed(readable);
		}
		String sessionId = field(started.body(), "id");
		if (sessionId == null || !sessionId.startsWith("upload:") || sessionId.matches(".*[\\s/].*")) {
			throw new WhatsAppSendFailed("Meta did not answer with an upload session.");
		}

		HttpResponse<String> uploaded = call(HttpRequest.newBuilder(URI.create(graphBaseUrl + "/" + sessionId))
				.timeout(TIMEOUT)
				.header("Authorization", "OAuth " + accessToken)
				.header("file_offset", "0")
				.POST(HttpRequest.BodyPublishers.ofByteArray(content)));
		if (uploaded.statusCode() >= 400) {
			String readable = readableError(uploaded);
			log.warn("Meta would not take the bytes of a template sample: {}", readable);
			throw new WhatsAppSendFailed(readable);
		}
		String handle = field(uploaded.body(), "h");
		if (handle == null || handle.isBlank()) {
			throw new WhatsAppSendFailed("Meta took the sample but named no handle for it.");
		}
		return handle;
	}

	/**
	 * Uploads one file to the temple's phone number, for a message to carry (T-200), and returns Meta's
	 * media id.
	 *
	 * <p>Meta's media reference: {@code POST /<PHONE_NUMBER_ID>/media} as {@code multipart/form-data} with
	 * {@code file}, {@code type} and {@code messaging_product=whatsapp}, answering {@code {"id":
	 * "<MEDIA_ID>"}}; a PDF may be up to 100 MB.
	 * https://developers.facebook.com/docs/whatsapp/cloud-api/reference/media
	 *
	 * @throws WhatsAppCredentialsRejected when Meta cannot be reached
	 * @throws WhatsAppSendFailed when Meta answers with an error, or with no id
	 */
	public String uploadMedia(String phoneNumberId, String accessToken, byte[] content, String fileName,
			String mimeType) {
		String boundary = "kms-" + java.util.UUID.randomUUID();
		java.io.ByteArrayOutputStream form = new java.io.ByteArrayOutputStream();
		writePart(form, boundary, "messaging_product", null, null, "whatsapp".getBytes(StandardCharsets.UTF_8));
		writePart(form, boundary, "type", null, null, mimeType.getBytes(StandardCharsets.UTF_8));
		writePart(form, boundary, "file", safeFileName(fileName), mimeType, content);
		form.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

		HttpResponse<String> response = call(HttpRequest.newBuilder(
						URI.create(graphBaseUrl + "/" + encode(phoneNumberId) + "/media"))
				.timeout(TIMEOUT)
				.header("Authorization", "Bearer " + accessToken)
				.header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.POST(HttpRequest.BodyPublishers.ofByteArray(form.toByteArray())));
		if (response.statusCode() >= 400) {
			throw new WhatsAppSendFailed(readableError(response));
		}
		String id = field(response.body(), "id");
		if (id == null || id.isBlank()) {
			throw new WhatsAppSendFailed("Meta took the file but named no id for it.");
		}
		return id;
	}

	private static void writePart(java.io.ByteArrayOutputStream form, String boundary, String name,
			String fileName, String contentType, byte[] value) {
		StringBuilder head = new StringBuilder("--").append(boundary).append("\r\n")
				.append("Content-Disposition: form-data; name=\"").append(name).append('"');
		if (fileName != null) {
			head.append("; filename=\"").append(fileName).append('"');
		}
		head.append("\r\n");
		if (contentType != null) {
			head.append("Content-Type: ").append(contentType).append("\r\n");
		}
		head.append("\r\n");
		form.writeBytes(head.toString().getBytes(StandardCharsets.UTF_8));
		form.writeBytes(value);
		form.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
	}

	/** A file name safe inside a quoted form-data header: letters, digits, dot, dash and underscore only. */
	static String safeFileName(String fileName) {
		String safe = fileName == null ? "" : fileName.replaceAll("[^A-Za-z0-9._-]", "-");
		return safe.isBlank() ? "document.pdf" : safe;
	}

	/** One text field of a JSON answer, or null when the answer is not JSON or has no such field. */
	private String field(String body, String name) {
		try {
			return body == null ? null : text(objectMapper.readTree(body), name);
		} catch (IOException e) {
			return null;
		}
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

	/**
	 * Meta's {@code error_subcode} for an edit Meta will not make to a template in its present state
	 * (T-169a).
	 *
	 * <p>Documented on Meta's error codes page as "2388039 - Message template status can't be changed":
	 * "This occurs when you try to edit a template whose status cannot be changed, for example, a
	 * template that is still in review. Wait until the template is approved or rejected before editing,
	 * and note that templates have a daily limit on the number of edits."
	 * https://developers.facebook.com/documentation/business-messaging/whatsapp/support/error-codes
	 *
	 * <p>So one code covers both "still in review" and "edited too often", and Meta's page does not tell
	 * them apart. The caller does, from the status it read before trying: see
	 * {@code TenantWhatsAppSettingsService}.
	 */
	static final int TEMPLATE_STATUS_CANNOT_BE_CHANGED = 2388039;

	/**
	 * What Meta holds under one template name in one language: its id, its review status, its category
	 * and its body (T-169a). The id is what an edit is addressed to, and we never store one.
	 *
	 * <p><strong>How, and where Meta says so.</strong> {@code GET /{WABA_ID}/message_templates} takes a
	 * {@code name} filter and a {@code fields} list, and answers a {@code data} array of templates each
	 * carrying {@code id}, {@code name}, {@code language}, {@code status}, {@code category} and
	 * {@code components}. Graph API reference, "Request Syntax: GET
	 * /&lt;WHATSAPP_BUSINESS_ACCOUNT_ID&gt;/message_templates ?category=, &amp;content=, &amp;language=,
	 * &amp;name=, …":
	 * https://developers.facebook.com/docs/graph-api/reference/whats-app-business-account/message_templates/
	 * and the template management guide's "Get all templates and specific fields":
	 * https://developers.facebook.com/documentation/business-messaging/whatsapp/templates/template-management
	 *
	 * <p><strong>The match is made here, exactly, on name and language.</strong> Meta's reference does
	 * not say whether {@code name} matches whole names or parts of them, and a part match would hand
	 * back {@code volunteer_shift_reminder} to a question about {@code shift_reminder}. Meta also keeps
	 * one template per language under a name. So whatever comes back is filtered to the one entry whose
	 * name and language are exactly the ones asked for, and anything else is ignored.
	 *
	 * @return empty when Meta lists no template with exactly this name and language
	 * @throws WhatsAppCredentialsRejected when Meta cannot be reached, as every call here does
	 * @throws WhatsAppSendFailed when Meta answers with an error, or with something that is not its list
	 */
	public Optional<HeldTemplate> findTemplate(String wabaId, String accessToken, String name, String languageCode) {
		HttpResponse<String> response = call(HttpRequest.newBuilder(
						URI.create(graphBaseUrl + "/" + encode(wabaId) + "/message_templates?name=" + encode(name)
								+ "&fields=id,name,language,status,category,components,rejected_reason"))
				.timeout(TIMEOUT)
				.header("Authorization", "Bearer " + accessToken)
				.GET());

		if (response.statusCode() >= 400) {
			String readable = readableError(response);
			log.warn("Meta would not list template {}: {}", name, readable);
			throw new WhatsAppSendFailed(readable);
		}
		JsonNode data;
		try {
			data = objectMapper.readTree(response.body()).path("data");
		} catch (IOException e) {
			throw new WhatsAppSendFailed("Meta's answer could not be read.", e);
		}
		if (!data.isArray()) {
			throw new WhatsAppSendFailed("Meta's answer was not a list of templates.");
		}
		for (JsonNode held : data) {
			String id = text(held, "id");
			if (id == null || !name.equals(text(held, "name")) || !languageCode.equals(text(held, "language"))) {
				continue;
			}
			String body = null;
			String headerFormat = null;
			for (JsonNode component : held.path("components")) {
				if ("BODY".equalsIgnoreCase(text(component, "type"))) {
					body = text(component, "text");
				}
				// T-200: the header's format, so a template Meta holds without the PDF header reads as not
				// matching ours, and one that has it reads as matching. Upper-cased because it is compared
				// with ours, which is Meta's own spelling.
				if ("HEADER".equalsIgnoreCase(text(component, "type")) && text(component, "format") != null) {
					headerFormat = text(component, "format").toUpperCase(Locale.ROOT);
				}
			}
			return Optional.of(new HeldTemplate(id, text(held, "status"), text(held, "category"), body,
					text(held, "rejected_reason"), headerFormat));
		}
		return Optional.empty();
	}

	/**
	 * Replaces the wording of a template Meta already holds, keeping its name, language and category
	 * (T-169a).
	 *
	 * <p><strong>Meta's rules, from the template management guide</strong>
	 * (https://developers.facebook.com/documentation/business-messaging/whatsapp/templates/template-management):
	 * <ul>
	 *   <li>The request is {@code POST /{TEMPLATE_ID}} with the new {@code components}, and "the API
	 *       replaces all components with the components in the edit request payload". The answer is
	 *       {@code {"success": true}}.</li>
	 *   <li>"Only templates with an APPROVED, REJECTED, or PAUSED status can be edited."</li>
	 *   <li>"You cannot edit the category of an approved template." So no category is sent: this changes
	 *       wording only, and a template Meta holds under a category of its own keeps it.</li>
	 *   <li>"Approved templates can be edited up to 10 times in a 30-day window, or 1 time in a 24-hour
	 *       window. Rejected or paused templates can be edited an unlimited number of times."</li>
	 *   <li>"After you edit an approved or paused template, the API automatically re-approves the
	 *       template unless it fails template review."</li>
	 * </ul>
	 *
	 * <p>Meta's refusal is sorted the way {@link #createTemplate}'s is, by code first:
	 * {@link #TEMPLATE_STATUS_CANNOT_BE_CHANGED} is its own outcome, and anything else is a refusal
	 * carrying Meta's sentence for the log and for the caller to translate. Meta unreachable is thrown.
	 */
	public TemplateEdit editTemplate(String templateId, String accessToken, String name, String bodyText,
			List<String> exampleValues) {
		return editTemplate(templateId, accessToken, name, bodyText, exampleValues, null);
	}

	/**
	 * The edit with a header (T-200). Meta's edit "replaces all components with the components in the edit
	 * request payload", so a template that has a header must send it on every edit, or the edit would take
	 * the header away.
	 */
	public TemplateEdit editTemplate(String templateId, String accessToken, String name, String bodyText,
			List<String> exampleValues, TemplateHeader header) {

		HttpResponse<String> response = post(
				graphBaseUrl + "/" + encode(templateId), accessToken,
				Map.of("components", components(bodyText, exampleValues, header)));

		if (response.statusCode() < 400) {
			if (saysSuccessFalse(response.body())) {
				log.warn("Meta answered the edit of template {} without confirming it: {}", name, response.body());
				return new TemplateEdit(EditOutcome.REFUSED, "Meta did not confirm the change.");
			}
			return new TemplateEdit(EditOutcome.EDITED, null);
		}
		JsonNode error = errorNode(response.body());
		String readable = readableError(response.statusCode(), response.body());
		log.debug("Meta answered the edit of template {} with HTTP {}: error.code={} error.error_subcode={} error_user_title={}",
				name, response.statusCode(), raw(error, "code"), raw(error, "error_subcode"), raw(error, "error_user_title"));
		if (error.path("error_subcode").asInt(0) == TEMPLATE_STATUS_CANNOT_BE_CHANGED) {
			log.warn("Meta will not change template {} in its present state: {}", name, readable);
			return new TemplateEdit(EditOutcome.STATUS_CANNOT_BE_CHANGED, readable);
		}
		log.warn("Meta refused new wording for template {}: {}", name, readable);
		return new TemplateEdit(EditOutcome.REFUSED, readable);
	}

	/** A 2xx whose body says {@code "success": false}, which Meta's page does not describe but is not an edit. */
	private boolean saysSuccessFalse(String body) {
		try {
			JsonNode success = body == null ? null : objectMapper.readTree(body).get("success");
			return success != null && !success.asBoolean(true);
		} catch (IOException e) {
			return false;
		}
	}

	/**
	 * One template as Meta holds it.
	 *
	 * @param id       Meta's id, which an edit is addressed to
	 * @param status   Meta's review status, e.g. {@code APPROVED}, {@code PENDING}, {@code REJECTED}
	 * @param category the category Meta holds it under, which may not be ours (T-168)
	 * @param bodyText the body as Meta holds it, with its {@code {{1}}} placeholders; null if it listed none
	 * @param rejectedReason Meta's {@code rejected_reason}, exactly as sent, e.g. {@code INVALID_FORMAT}; null
	 *     when Meta sent none (T-178). The Graph API reference for the template node lists it as "The reason
	 *     the message template was rejected. enum {ABUSIVE_CONTENT, INVALID_FORMAT, NONE, PROMOTIONAL,
	 *     TAG_CONTENT_MISMATCH, SCAM}":
	 *     https://developers.facebook.com/docs/graph-api/reference/whats-app-business-hsm/ . Asked for in the
	 *     same lookup Reload makes rather than in a second call, so the operator's status copy and Reload's
	 *     comparison cannot read two different answers. Nothing in Reload reads it.
	 */
	public record HeldTemplate(String id, String status, String category, String bodyText, String rejectedReason,
			String headerFormat) {

		/**
		 * A template with no header (T-200), the shape every one of these had before the purchase order
		 * gained its PDF.
		 *
		 * @param headerFormat (on the canonical constructor) the format of the HEADER component Meta lists,
		 *     upper-cased, e.g. {@code DOCUMENT}; null when Meta lists no header or one with no format
		 */
		public HeldTemplate(String id, String status, String category, String bodyText, String rejectedReason) {
			this(id, status, category, bodyText, rejectedReason, null);
		}
	}

	/**
	 * What became of new wording sent for a template Meta already holds.
	 *
	 * <p>{@link #STATUS_CANNOT_BE_CHANGED} is Meta's documented 2388039, which covers both a template
	 * still in review and one edited too often; see {@link #TEMPLATE_STATUS_CANNOT_BE_CHANGED}.
	 */
	public enum EditOutcome { EDITED, STATUS_CANNOT_BE_CHANGED, REFUSED }

	/**
	 * @param metaReason Meta's sentence when it did not edit, for the log and the caller to translate;
	 *     null when it did
	 */
	public record TemplateEdit(EditOutcome outcome, String metaReason) {
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
