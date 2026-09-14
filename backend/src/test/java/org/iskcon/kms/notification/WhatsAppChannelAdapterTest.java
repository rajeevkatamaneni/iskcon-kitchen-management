package org.iskcon.kms.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.iskcon.kms.document.DocumentService;
import org.iskcon.kms.document.DocumentView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * When "this temple's WhatsApp actually works" gets written down, and when it does not (T-136), and the
 * purchase order's PDF going with its message (T-200).
 *
 * <p>Rajeev's ruling of 2026-09-10 is that the Send on WhatsApp button appears "only after a message
 * has actually gone through it successfully", not merely configured. The whole of that ruling rests
 * on one line in {@link WhatsAppChannelAdapter}, so what this fixes in place is exactly where that
 * line sits: after Meta accepted the message, never before, and never on any other path.
 *
 * <p>A plain unit test with Mockito and no Spring context, deliberately. The question is which of
 * four paths through one method reaches one call, which needs no database and no HTTP — and a
 * {@code @MockBean} here would have cost the suite a whole extra application context for something
 * two mocks answer (see the note on context caching in {@code AbstractIntegrationTest}). The
 * database side of the same feature — the column, its RLS, and what NULL means — is
 * {@link WhatsAppLastSentIT}, which does need a real PostgreSQL.
 *
 * <p><strong>The T-136 tests use a shift reminder, not a purchase order, since T-200.</strong> They were
 * written with {@code po_delivery} and no sheet. Since T-200 a purchase order is never sent without its
 * PDF, so that message would now stop before Meta for want of a sheet and the tests would prove nothing
 * about the stamp. Their question is about any message, so they ask it of one with no header.
 *
 * <p><strong>The T-200 tests use the real {@link MetaWhatsAppClient}</strong>, pointed at a JDK
 * {@link HttpServer} on 127.0.0.1 that plays Meta's media upload and messages endpoints, so the multipart
 * body and the header component on the wire are the ones production builds. Nothing reaches Meta.
 */
class WhatsAppChannelAdapterTest {

	private TenantWhatsAppSettingsService settings;
	private MetaWhatsAppClient meta;
	private WhatsAppChannelAdapter adapter;
	private OutboundMessage message;
	private ObjectProvider<DocumentService> documents;
	private HttpServer metaServer;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		settings = mock(TenantWhatsAppSettingsService.class);
		meta = mock(MetaWhatsAppClient.class);
		documents = mock(ObjectProvider.class);
		adapter = new WhatsAppChannelAdapter(settings, meta, documents, "en");
		Map<String, Object> params = Map.of("title", "Kitchen seva", "date", "Sunday", "time", "9am",
				"location", "Main kitchen");
		message = new OutboundMessage(NotificationTemplate.VOLUNTEER_SHIFT_REMINDER, params,
				NotificationTemplate.VOLUNTEER_SHIFT_REMINDER.render(params));
	}

	@AfterEach
	void tearDown() {
		if (metaServer != null) {
			metaServer.stop(0);
		}
	}

	private void givenAConnectedTemple() {
		when(settings.sendingIdentity()).thenReturn(
				Optional.of(new TenantWhatsAppSettingsService.SendingIdentity("phone-1", "token-1")));
	}

	@Test
	@DisplayName("a message Meta accepted is what stamps the temple as able to send")
	void stampsOnlyAfterMetaAcceptedTheMessage() {
		givenAConnectedTemple();
		when(meta.sendTemplate(anyString(), anyString(), anyString(), anyString(), anyString(), anyList()))
				.thenReturn("wamid.HBgM");

		SendResult result = adapter.send("+919812345678", message);

		assertThat(result.sent()).isTrue();
		assertThat(result.providerMessageId()).isEqualTo("wamid.HBgM");
		verify(settings).markMessageSent();
	}

	/**
	 * The case the whole ruling is about.
	 *
	 * <p>Every one of these failures happens with credentials that are perfectly valid — an
	 * unapproved template, a recipient outside Meta's test list, a spent messaging tier. They are
	 * precisely the temples where {@code whatsapp_verified_at} looks immaculate and no message has
	 * ever arrived anywhere, which is why gating the button on that date would have defeated the
	 * ruling exactly as written.
	 */
	@Test
	@DisplayName("Meta refusing the message stamps nothing, and falls through to the next channel")
	void refusalStampsNothing() {
		givenAConnectedTemple();
		when(meta.sendTemplate(anyString(), anyString(), anyString(), anyString(), anyString(), anyList()))
				.thenThrow(new RuntimeException("(#132001) Template name does not exist"));

		SendResult result = adapter.send("+919812345678", message);

		assertThat(result.sent()).isFalse();
		assertThat(result.detail()).contains("Template name does not exist");
		verify(settings, never()).markMessageSent();
	}

	@Test
	@DisplayName("a temple that has connected nothing stamps nothing and never calls Meta")
	void unconnectedTempleStampsNothing() {
		when(settings.sendingIdentity()).thenReturn(Optional.empty());

		SendResult result = adapter.send("+919812345678", message);

		assertThat(result.sent()).isFalse();
		verify(settings, never()).markMessageSent();
		verify(meta, never()).sendTemplate(any(), any(), any(), any(), any(), any());
	}

	/**
	 * And the stamp is outside the try, which is not tidying.
	 *
	 * <p>If writing the column could be caught as a send failure, the adapter would report FAILED
	 * for a message Meta has already accepted — and the cascade would then send the same purchase
	 * order to the same vendor again by SMS. The vendor gets it twice because a column could not be
	 * written. So the failure has to escape: the dispatcher's transaction rolls back, nothing claims
	 * to have been sent, and the job retries the whole thing rather than half of it.
	 */
	@Test
	@DisplayName("a stamp that fails is not swallowed as a send failure")
	void aFailedStampIsNotReportedAsAFailedSend() {
		givenAConnectedTemple();
		when(meta.sendTemplate(anyString(), anyString(), anyString(), anyString(), anyString(), anyList()))
				.thenReturn("wamid.HBgM");
		org.mockito.Mockito.doThrow(new IllegalStateException("connection closed"))
				.when(settings).markMessageSent();

		assertThatThrownBy(() -> adapter.send("+919812345678", message))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("connection closed");
	}
	// ---- a message typed over several lines (T-180) --------------------------------------------------

	/**
	 * A coordinator's broadcast as a coordinator types it: two lines, a blank line between, and a double
	 * space. Meta refuses a template parameter with a line break in it, so before T-180 this could not go
	 * on WhatsApp at all.
	 */
	private static final String TWO_LINE_MESSAGE = "Please arrive at 5am.\r\n\r\nBring  an apron.";

	private static OutboundMessage twoLineBroadcast() {
		Map<String, Object> params = Map.of("title", "Kitchen seva", "message", TWO_LINE_MESSAGE);
		return new OutboundMessage(NotificationTemplate.SHIFT_BROADCAST, params,
				NotificationTemplate.SHIFT_BROADCAST.render(params));
	}

	@Test
	@DisplayName("a two-line broadcast reaches Meta on one line, with each run of spaces and line breaks made one space")
	@SuppressWarnings("unchecked")
	void aTwoLineBroadcastIsFlattenedForWhatsApp() {
		givenAConnectedTemple();
		when(meta.sendTemplate(anyString(), anyString(), anyString(), anyString(), anyString(), anyList()))
				.thenReturn("wamid.HBgM");

		adapter.send("+919812345678", twoLineBroadcast());

		ArgumentCaptor<List<String>> sent = ArgumentCaptor.forClass(List.class);
		verify(meta).sendTemplate(anyString(), anyString(), anyString(), eq("shift_broadcast"), anyString(),
				sent.capture());
		assertThat(sent.getValue()).containsExactly("Kitchen seva", "Please arrive at 5am. Bring an apron.");
		assertThat(sent.getValue()).allSatisfy(value -> assertThat(value)
				.doesNotContain("\n").doesNotContain("\r").doesNotContain("  "));
	}

	/**
	 * The other half, and the reason the flattening is in the WhatsApp adapter and nowhere earlier. Email
	 * is driven for real around a mocked relay and must carry the admin's line breaks as typed. SMS has no
	 * provider and sends nothing today; what it is handed is the same message, so the rendered body is
	 * checked after the WhatsApp send to show that send changed nothing the other channels read.
	 */
	@Test
	@DisplayName("the same broadcast keeps its line breaks by email, and the message SMS is handed is untouched by the WhatsApp send")
	@SuppressWarnings("unchecked")
	void emailAndSmsKeepTheLineBreaks() throws Exception {
		OutboundMessage broadcast = twoLineBroadcast();
		givenAConnectedTemple();
		when(meta.sendTemplate(anyString(), anyString(), anyString(), anyString(), anyString(), anyList()))
				.thenReturn("wamid.HBgM");
		adapter.send("+919812345678", broadcast);

		assertThat(broadcast.rendered().body())
				.isEqualTo("Message from the coordinator of your Kitchen seva shift: \"" + TWO_LINE_MESSAGE
						+ "\" This message went to everyone on the shift.");
		assertThat(broadcast.orderedParameters()).containsExactly("Kitchen seva", TWO_LINE_MESSAGE);

		JavaMailSender relay = mock(JavaMailSender.class);
		when(relay.createMimeMessage()).thenAnswer(i -> new MimeMessage((Session) null));
		ObjectProvider<JavaMailSender> relayProvider = mock(ObjectProvider.class);
		when(relayProvider.getIfAvailable()).thenReturn(relay);
		TenantEmailIdentityService identities = mock(TenantEmailIdentityService.class);
		when(identities.current()).thenReturn(new TenantEmailIdentityService.Identity("ISKCON South Bengaluru", null));
		SmtpEmailAdapter email = new SmtpEmailAdapter(relayProvider, identities, "noreply@kms.test", "ISKCON Kitchen");

		assertThat(email.send("volunteer@example.org", broadcast).sent()).isTrue();

		ArgumentCaptor<MimeMessage> mailed = ArgumentCaptor.forClass(MimeMessage.class);
		verify(relay).send(mailed.capture());
		assertThat((String) mailed.getValue().getContent()).contains(TWO_LINE_MESSAGE);
	}

	@Test
	@DisplayName("a value with no line break or double space goes to Meta exactly as it is")
	void anOrdinaryValueIsUnchanged() {
		assertThat(WhatsAppChannelAdapter.whatsappParameters(message)).isEqualTo(message.orderedParameters());
	}

	// ---- the purchase order's PDF (T-200) ---------------------------------------------------------------

	/** Bytes shaped like a PDF and unique enough that finding them in the upload means they were sent as stored. */
	private static final byte[] SHEET = ("%PDF-1.4\n% T-200 Kannada sheet for PO-2026-0042 "
			+ UUID.randomUUID() + "\n%%EOF\n").getBytes(StandardCharsets.US_ASCII);

	private static final UUID SHEET_ID = UUID.fromString("00000000-0000-0000-0000-000000000200");

	/** One request the stub received. */
	private record Received(String method, String path, String contentType, String authorization, byte[] body) {
	}

	private final List<Received> received = Collections.synchronizedList(new ArrayList<>());

	/** Meta's media and messages endpoints on 127.0.0.1, answering as Meta documents. */
	private MetaWhatsAppClient realClientAgainstAStub() throws Exception {
		metaServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		metaServer.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			received.add(new Received(exchange.getRequestMethod(), path,
					exchange.getRequestHeaders().getFirst("Content-Type"),
					exchange.getRequestHeaders().getFirst("Authorization"),
					exchange.getRequestBody().readAllBytes()));
			String answer = path.endsWith("/media") ? "{\"id\":\"media-t200\"}"
					: path.endsWith("/messages") ? "{\"messaging_product\":\"whatsapp\",\"messages\":[{\"id\":\"wamid.T200\"}]}"
					: "{\"error\":{\"message\":\"Unsupported post request.\",\"code\":100}}";
			int status = path.endsWith("/media") || path.endsWith("/messages") ? 200 : 400;
			byte[] out = answer.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, out.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(out);
			}
		});
		metaServer.start();
		return new MetaWhatsAppClient(new ObjectMapper(), "http://127.0.0.1:" + metaServer.getAddress().getPort());
	}

	private static OutboundMessage purchaseOrder(Object documentId) {
		Map<String, Object> params = new java.util.HashMap<>(Map.of("poNumber", "PO-2026-0042",
				"vendor", "Govind Wholesale", "summary", "1 item(s): Rice", "raised", "1 Aug 2026",
				"neededBy", "20 Aug 2026"));
		if (documentId != null) {
			params.put("documentId", documentId.toString());
		}
		return new OutboundMessage(NotificationTemplate.PO_DELIVERY, params, NotificationTemplate.PO_DELIVERY.render(params));
	}

	private DocumentService aSheetThatIs(String status) {
		DocumentService service = mock(DocumentService.class);
		when(documents.getIfAvailable()).thenReturn(service);
		when(service.get(SHEET_ID)).thenReturn(new DocumentView(SHEET_ID, "PURCHASE_ORDER_PDF", null,
				UUID.randomUUID(), 2, "kn", null, status, null, Instant.now(), "READY".equals(status) ? Instant.now() : null));
		when(service.openForDownload(SHEET_ID)).thenAnswer(i -> new ByteArrayInputStream(SHEET));
		return service;
	}

	static int indexOf(byte[] haystack, byte[] needle) {
		outer:
		for (int i = 0; i <= haystack.length - needle.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}

	@Test
	@DisplayName("a purchase order uploads its sheet to the temple's number, then sends it as the header's document")
	void aPurchaseOrderSendsItsSheetAsTheHeaderDocument() throws Exception {
		adapter = new WhatsAppChannelAdapter(settings, realClientAgainstAStub(), documents, "en");
		givenAConnectedTemple();
		aSheetThatIs("READY");

		SendResult result = adapter.send("+919812345678", purchaseOrder(SHEET_ID));

		assertThat(result.sent()).as(String.valueOf(result.detail())).isTrue();
		assertThat(result.providerMessageId()).isEqualTo("wamid.T200");
		assertThat(received).extracting(Received::path).containsExactly("/phone-1/media", "/phone-1/messages");

		// The upload: multipart, to the phone number, carrying the stored bytes exactly.
		Received upload = received.get(0);
		assertThat(upload.contentType()).startsWith("multipart/form-data; boundary=");
		assertThat(indexOf(upload.body(), SHEET)).as("the stored sheet's bytes, unchanged, in the upload").isNotNegative();
		String form = new String(upload.body(), StandardCharsets.ISO_8859_1);
		assertThat(form).contains("name=\"messaging_product\"\r\n\r\nwhatsapp\r\n")
				.contains("name=\"type\"\r\n\r\napplication/pdf\r\n")
				.contains("name=\"file\"; filename=\"PO-2026-0042.pdf\"\r\nContent-Type: application/pdf\r\n");

		// The send: the header names that upload, and the body is the five approved values.
		JsonNode sent = new ObjectMapper().readTree(received.get(1).body());
		JsonNode components = sent.path("template").path("components");
		assertThat(sent.path("template").path("name").asText()).isEqualTo("po_delivery");
		assertThat(components.get(0).path("type").asText()).isEqualTo("header");
		JsonNode document = components.get(0).path("parameters").get(0);
		assertThat(document.path("type").asText()).isEqualTo("document");
		assertThat(document.path("document").path("id").asText()).isEqualTo("media-t200");
		assertThat(document.path("document").path("filename").asText()).isEqualTo("PO-2026-0042.pdf");
		assertThat(components.get(1).path("type").asText()).isEqualTo("body");
		List<String> bodyValues = new ArrayList<>();
		components.get(1).path("parameters").forEach(p -> bodyValues.add(p.path("text").asText()));
		assertThat(bodyValues).containsExactly("PO-2026-0042", "Govind Wholesale", "1 item(s): Rice", "1 Aug 2026", "20 Aug 2026");
		verify(settings).markMessageSent();
	}

	@Test
	@DisplayName("a purchase order whose sheet is still being made is not sent at all, and says why")
	void aSheetNotReadyIsNeverSentWithout() throws Exception {
		adapter = new WhatsAppChannelAdapter(settings, realClientAgainstAStub(), documents, "en");
		givenAConnectedTemple();
		aSheetThatIs("PENDING");

		SendResult result = adapter.send("+919812345678", purchaseOrder(SHEET_ID));

		assertThat(result.sent()).isFalse();
		assertThat(result.detail()).isEqualTo(WhatsAppChannelAdapter.SHEET_NOT_READY);
		assertThat(received).as("nothing uploaded, nothing sent").isEmpty();
		verify(settings, never()).markMessageSent();
	}

	@Test
	@DisplayName("a purchase order that names no sheet is not sent at all, and says why")
	void noSheetIsNeverSentWithout() throws Exception {
		adapter = new WhatsAppChannelAdapter(settings, realClientAgainstAStub(), documents, "en");
		givenAConnectedTemple();

		SendResult result = adapter.send("+919812345678", purchaseOrder(null));

		assertThat(result.sent()).isFalse();
		assertThat(result.detail()).isEqualTo(WhatsAppChannelAdapter.NO_SHEET);
		assertThat(received).isEmpty();
	}

	@Test
	@DisplayName("Meta refusing the upload is a refused send: no message goes, and nothing is stamped")
	void aRefusedUploadIsARefusedSend() {
		givenAConnectedTemple();
		aSheetThatIs("READY");
		when(meta.uploadMedia(anyString(), anyString(), any(), anyString(), anyString()))
				.thenThrow(new MetaWhatsAppClient.WhatsAppSendFailed("(#131053) Media upload error"));

		SendResult result = adapter.send("+919812345678", purchaseOrder(SHEET_ID));

		assertThat(result.sent()).isFalse();
		verify(meta, never()).sendTemplate(any(), any(), any(), any(), any(), any(), any());
		verify(settings, never()).markMessageSent();
	}
}
