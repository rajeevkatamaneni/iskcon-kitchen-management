package org.iskcon.kms.purchaseorder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.document.DocumentService;
import org.iskcon.kms.document.DocumentStorage;
import org.iskcon.kms.notification.MetaWhatsAppClient;
import org.iskcon.kms.notification.NotificationTemplate;
import org.iskcon.kms.notification.OutboundMessage;
import org.iskcon.kms.notification.SendResult;
import org.iskcon.kms.notification.TenantWhatsAppSettingsService;
import org.iskcon.kms.notification.WhatsAppChannelAdapter;
import org.iskcon.kms.tenancy.TenantSecretStore;
import org.iskcon.kms.notification.DeliveryStatus;
import org.iskcon.kms.notification.NotificationDeliveryService;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.testsupport.StubTokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * WhatsApp PO delivery (E5-S7): sending through the notification service records a linked
 * WHATSAPP_SENT event and is audited; a delivery webhook reflects the outcome onto the PO trail and
 * flags an unreachable vendor; a rate guard blocks an immediate resend; a closed PO can't be sent.
 * A mocked {@link Scheduler} keeps the enqueue path hermetic (the notification stays PENDING).
 */
@AutoConfigureMockMvc
class PurchaseOrderWhatsAppIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private NotificationDeliveryService deliveryService;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@MockBean
	private Scheduler scheduler;

	@Autowired
	private DocumentStorage storage;

	@Autowired
	private ObjectProvider<DocumentService> documents;

	@Autowired
	private TenantWhatsAppSettingsService whatsappSettings;

	@Autowired
	private TenantSecretStore secrets;

	@Autowired
	private ObjectMapper objectMapper;

	private HttpServer metaServer;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID staffId;
	private UUID rice;
	private UUID vendor;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		staffId = admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-staff-a', 'Staff A', 'staff-a@example.com', '+919876500081', 'KITCHEN_STAFF', 'ACTIVE')
				RETURNING id
				""", UUID.class, tenant);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-vol-a', 'Vol A', 'vol-a@example.com', '+919876500082', 'VOLUNTEER', 'ACTIVE')
				""", tenant);
		rice = admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, 'Rice', 'Grains', 'KG') RETURNING id
				""", UUID.class, tenant);
		vendor = admin.queryForObject("""
				INSERT INTO vendors (tenant_id, name, phone) VALUES (?, 'Govind Wholesale', '+919812345678')
				RETURNING id
				""", UUID.class, tenant);
		signIn("uid-staff-a");
	}

	@AfterEach
	void tearDown() {
		if (metaServer != null) {
			metaServer.stop(0);
		}
		TenantContext.clear();
		secrets.deleteAll(tenant);
		admin.execute("DELETE FROM documents");
		// T-200: a sheet rendered at the press in a vendor's language caches its translated labels per temple.
		admin.execute("DELETE FROM po_label_translations");
		admin.execute("DELETE FROM document_label_translations");
		admin.execute("DELETE FROM po_events");
		admin.execute("DELETE FROM notification_attempts");
		admin.execute("DELETE FROM notifications");
		admin.execute("DELETE FROM purchase_order_lines");
		admin.execute("DELETE FROM purchase_orders");
		admin.execute("DELETE FROM po_sequence");
		admin.execute("DELETE FROM vendors");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM tenant_settings");
		// Anything that moved through the stock ledger is tracked now, so the item rows exist
		// even where the test never asked for them, and they hold the ingredient down.
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("sending a SENT PO records a linked WHATSAPP_SENT trail event and is audited")
	void sendRecordsTrailAndAudit() throws Exception {
		UUID poId = sentPo("PO-2026-0042");

		String body = mvc.perform(whatsapp(poId)).andExpect(status().isAccepted())
				.andReturn().getResponse().getContentAsString();
		UUID notificationId = UUID.fromString(
				new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("notificationId").asText());

		// A trail event, linked to the notification.
		UUID linkedPo = admin.queryForObject(
				"SELECT po_id FROM po_events WHERE notification_id = ? AND event_type = 'WHATSAPP_SENT'",
				UUID.class, notificationId);
		assert poId.equals(linkedPo);

		// Audited with the actor.
		Integer audits = admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = 'PO_WHATSAPP_SENT' AND entity_id = ?",
				Integer.class, poId);
		assert audits == 1 : "send should be audited once";
	}

	@Test
	@DisplayName("sending a draft transitions it to SENT as part of delivering it")
	void sendingDraftTransitionsIt() throws Exception {
		UUID poId = admin.queryForObject("""
				INSERT INTO purchase_orders (tenant_id, po_number, vendor_id, status, created_by)
				VALUES (?, 'PO-2026-0043', ?, 'DRAFT', ?) RETURNING id
				""", UUID.class, tenant, vendor, staffId);
		line(poId);

		mvc.perform(whatsapp(poId)).andExpect(status().isAccepted());
		mvc.perform(authed(get("/api/v1/purchase-orders/{id}", poId)))
				.andExpect(jsonPath("$.order.status").value("SENT"));
	}

	@Test
	@DisplayName("an immediate resend is rate-limited")
	void resendRateLimited() throws Exception {
		UUID poId = sentPo("PO-2026-0044");
		mvc.perform(whatsapp(poId)).andExpect(status().isAccepted());
		mvc.perform(whatsapp(poId))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400056"));
	}

	@Test
	@DisplayName("a cancelled PO cannot be sent to a vendor")
	void cancelledCannotBeSent() throws Exception {
		UUID poId = admin.queryForObject("""
				INSERT INTO purchase_orders (tenant_id, po_number, vendor_id, status, cancel_reason,
					cancelled_at, created_by)
				VALUES (?, 'PO-2026-0045', ?, 'CANCELLED', 'duplicate', now(), ?) RETURNING id
				""", UUID.class, tenant, vendor, staffId);
		line(poId);
		mvc.perform(whatsapp(poId))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400055"));
	}

	@Test
	@DisplayName("a delivered webhook lands on the PO trail")
	void webhookDeliveredLandsOnTrail() throws Exception {
		UUID poId = sentPo("PO-2026-0046");
		UUID notificationId = send(poId);
		setProviderMessageId(notificationId, "wamid.DELIVERED-1");

		deliveryService.applyStatus("wamid.DELIVERED-1", DeliveryStatus.DELIVERED);
		TenantContext.clear();

		mvc.perform(authed(get("/api/v1/purchase-orders/{id}", poId)))
				.andExpect(jsonPath("$.events[?(@.eventType=='WHATSAPP_DELIVERED')]").exists());
	}

	@Test
	@DisplayName("a failed webhook flags the vendor and surfaces a failure on the trail")
	void webhookFailedFlagsVendor() throws Exception {
		UUID poId = sentPo("PO-2026-0047");
		UUID notificationId = send(poId);
		setProviderMessageId(notificationId, "wamid.FAILED-1");

		deliveryService.applyStatus("wamid.FAILED-1", DeliveryStatus.FAILED);
		TenantContext.clear();

		mvc.perform(authed(get("/api/v1/purchase-orders/{id}", poId)))
				.andExpect(jsonPath("$.events[?(@.eventType=='WHATSAPP_FAILED')]").exists());
		Boolean reachable = admin.queryForObject(
				"SELECT whatsapp_reachable FROM vendors WHERE id = ?", Boolean.class, vendor);
		assert Boolean.FALSE.equals(reachable) : "vendor should be flagged unreachable";
	}

	/**
	 * Whether the order screen may offer Send on WhatsApp at all (T-136).
	 *
	 * <p>Rajeev's ruling, 2026-09-10: the button is shown "only after a message has actually gone
	 * through it successfully", not merely configured. So the fact travels on this payload, and it
	 * travels here rather than on {@code GET /api/v1/settings/whatsapp} on purpose — that endpoint
	 * is behind {@code MANAGE_TEMPLE_SETTINGS}, which the Kitchen Staff account signed in below does
	 * not hold, and reading it from there would have hidden the button for a reason that has nothing
	 * to do with WhatsApp.
	 *
	 * <p>The temple here is fully configured — credentials verified, templates submitted, a callback
	 * seen — and has still sent nothing, which is exactly the state the ruling distinguishes.
	 */
	@Test
	@DisplayName("the order says whether this temple's WhatsApp has ever actually sent anything")
	void orderCarriesWhetherWhatsAppHasEverSent() throws Exception {
		UUID poId = sentPo("PO-2026-0049");
		admin.update("""
				INSERT INTO tenant_settings (tenant_id, whatsapp_phone_number_id, whatsapp_waba_id,
						whatsapp_webhook_token, whatsapp_verified_at, whatsapp_templates_submitted_at,
						whatsapp_webhook_seen_at)
				VALUES (?, 'phone-1', 'waba-1', 'tok-1', now(), now(), now())
				""", tenant);

		mvc.perform(authed(get("/api/v1/purchase-orders/{id}", poId)))
				.andExpect(jsonPath("$.whatsappEverSent").value(false));

		// One message actually out of the door, and the offer appears — for a reader with
		// MANAGE_PURCHASE_ORDERS and nothing else.
		admin.update("UPDATE tenant_settings SET whatsapp_last_sent_at = now() WHERE tenant_id = ?", tenant);
		mvc.perform(authed(get("/api/v1/purchase-orders/{id}", poId)))
				.andExpect(jsonPath("$.whatsappEverSent").value(true));
	}

	/**
	 * The field is about the temple and never about this order, pinned on the one case that reads as
	 * a defect until you know that (T-370).
	 *
	 * <p>The staging run of 2026-09-19 reported {@code whatsappEverSent: true} on a brand-new DRAFT
	 * that had never been sent, to a vendor created minutes earlier, and filed it as a wrong value.
	 * It is the right value: that temple had sent WhatsApp messages before, and this says so. What
	 * says whether THIS order went out is {@code order.sentAt}, which is null on the very same
	 * payload — so the two are asserted together here, because reading one as the other is the
	 * mistake, and a test that only checked the true would not have shown the difference.
	 *
	 * <p>{@link #orderCarriesWhetherWhatsAppHasEverSent} covers the same field on an order that WAS
	 * sent. Neither of them could have caught this, because both used a sent order; a draft is the
	 * case where the two facts can disagree, and it had no test.
	 */
	@Test
	@DisplayName("a draft that was never sent still reports the temple's WhatsApp, and its own sentAt stays null")
	void draftNeverSentStillCarriesTheTemplesWhatsApp() throws Exception {
		UUID poId = draftPo("PO-2026-0050");
		admin.update("""
				INSERT INTO tenant_settings (tenant_id, whatsapp_phone_number_id, whatsapp_waba_id,
						whatsapp_webhook_token, whatsapp_verified_at, whatsapp_last_sent_at)
				VALUES (?, 'phone-1', 'waba-1', 'tok-1', now(), now())
				""", tenant);

		mvc.perform(authed(get("/api/v1/purchase-orders/{id}", poId)))
				.andExpect(jsonPath("$.order.status").value("DRAFT"))
				// This order has asked nothing of anybody.
				.andExpect(jsonPath("$.order.sentAt").doesNotExist())
				// The temple's WhatsApp has, which is the whole of what this field claims.
				.andExpect(jsonPath("$.whatsappEverSent").value(true));

		// And it follows the temple, not the order: take the temple's send away and the same
		// untouched draft reports false.
		admin.update("UPDATE tenant_settings SET whatsapp_last_sent_at = NULL WHERE tenant_id = ?", tenant);
		mvc.perform(authed(get("/api/v1/purchase-orders/{id}", poId)))
				.andExpect(jsonPath("$.order.sentAt").doesNotExist())
				.andExpect(jsonPath("$.whatsappEverSent").value(false));
	}

	@Test
	@DisplayName("a volunteer cannot send POs on WhatsApp")
	void volunteerForbidden() throws Exception {
		UUID poId = sentPo("PO-2026-0048");
		signIn("uid-vol-a");
		mvc.perform(whatsapp(poId)).andExpect(status().isForbidden());
	}

	// ---- T-200: which sheet goes, and that it goes as the header's document ----------------------------

	/**
	 * A sheet as the generator leaves it: bytes in storage and a READY row pointing at them. Written through
	 * the superuser, because what is under test is which row the send picks, not how a sheet is rendered.
	 */
	private UUID sheet(UUID poId, int version, String language, String status, byte[] content) {
		String key = null;
		if (content != null) {
			key = storage.store("t200/" + UUID.randomUUID() + ".pdf", content, "application/pdf");
		}
		return admin.queryForObject("""
				INSERT INTO documents (tenant_id, kind, po_id, version, language, status, storage_key, ready_at)
				VALUES (?, 'PURCHASE_ORDER_PDF', ?, ?, ?, ?, ?, CASE WHEN ?::text = 'READY' THEN now() END)
				RETURNING id
				""", UUID.class, tenant, poId, version, language, status, key, status);
	}

	private static byte[] pdf(String label) {
		return ("%PDF-1.4\n% " + label + " " + UUID.randomUUID() + "\n%%EOF\n").getBytes(StandardCharsets.US_ASCII);
	}

	/** The parameters the send stored on its notification, as the dispatcher will read them. */
	private Map<String, Object> storedParams(UUID notificationId) throws Exception {
		String json = admin.queryForObject("SELECT params::text FROM notifications WHERE id = ?", String.class, notificationId);
		return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
		});
	}

	@Test
	@DisplayName("an order translated for its vendor sends the translated sheet, even when an English copy was printed later")
	void aTranslatedOrderSendsTheTranslatedSheet() throws Exception {
		admin.update("UPDATE vendors SET preferred_language = 'kn' WHERE id = ?", vendor);
		UUID poId = sentPo("PO-2026-0200");
		sheet(poId, 1, "en", "READY", pdf("english v1"));
		UUID kannada = sheet(poId, 2, "kn", "READY", pdf("kannada v2"));
		sheet(poId, 3, "en", "READY", pdf("english v3, printed for the store room"));
		sheet(poId, 4, "kn", "FAILED", null);

		UUID notificationId = send(poId);

		assertThat(storedParams(notificationId).get("documentId")).isEqualTo(kannada.toString());
	}

	@Test
	@DisplayName("an order for a vendor who reads English sends the English original, not a later translation")
	void anUntranslatedOrderSendsTheEnglishOriginal() throws Exception {
		UUID poId = sentPo("PO-2026-0201");
		sheet(poId, 1, "kn", "READY", pdf("kannada v1"));
		UUID english = sheet(poId, 2, "en", "READY", pdf("english v2"));
		sheet(poId, 3, "hi", "READY", pdf("hindi v3"));

		UUID notificationId = send(poId);

		assertThat(storedParams(notificationId).get("documentId")).isEqualTo(english.toString());
	}

	/**
	 * T-200, sent back for the draft timing: a sheet that is not READY when Send on WhatsApp is pressed is
	 * made there and then, so the message never names a sheet the worker has not rendered yet.
	 */
	@Test
	@DisplayName("an order with no sheet at all has one made at the press, READY and stored, and the message names it")
	void anOrderWithNoSheetHasOneMadeAtThePress() throws Exception {
		UUID poId = sentPo("PO-2026-0202");

		UUID notificationId = send(poId);

		List<Map<String, Object>> sheets = admin.queryForList(
				"SELECT id, status, storage_key, language FROM documents WHERE po_id = ? AND kind = 'PURCHASE_ORDER_PDF'", poId);
		assertThat(sheets).hasSize(1);
		assertThat(sheets.get(0).get("status")).isEqualTo("READY");
		assertThat(sheets.get(0).get("storage_key")).isNotNull();
		assertThat(storedParams(notificationId).get("documentId")).isEqualTo(sheets.get(0).get("id").toString());
	}

	/**
	 * The case that was sent back. Sending a draft asks for its sheet, and the worker renders it in its own
	 * time; the message used to be queued naming that PENDING sheet, and the send then often failed in the
	 * background. Now the sheet the draft asked for is rendered in the vendor's language before the message is
	 * queued, and the message names it READY.
	 */
	@Test
	@DisplayName("a draft sent on WhatsApp with no READY sheet is sent with its sheet made there and then, READY, in the vendor's language")
	void aDraftWithNoReadySheetSendsWithItsPdf() throws Exception {
		admin.update("UPDATE vendors SET preferred_language = 'kn' WHERE id = ?", vendor);
		UUID poId = admin.queryForObject("""
				INSERT INTO purchase_orders (tenant_id, po_number, vendor_id, status, created_by)
				VALUES (?, 'PO-2026-0204', ?, 'DRAFT', ?) RETURNING id
				""", UUID.class, tenant, vendor, staffId);
		line(poId);
		assertThat(admin.queryForObject("SELECT count(*) FROM documents WHERE po_id = ?", Integer.class, poId)).isZero();

		UUID notificationId = send(poId);

		assertThat(admin.queryForObject("SELECT status FROM purchase_orders WHERE id = ?", String.class, poId)).isEqualTo("SENT");
		String documentId = (String) storedParams(notificationId).get("documentId");
		Map<String, Object> sheet = admin.queryForMap(
				"SELECT status, language, storage_key FROM documents WHERE id = ?::uuid", documentId);
		assertThat(sheet.get("status")).as("READY before the message was queued").isEqualTo("READY");
		assertThat(sheet.get("language")).isEqualTo("kn");
		try (var in = storage.open((String) sheet.get("storage_key"))) {
			assertThat(in.readAllBytes()).as("the PDF the message will carry exists").isNotEmpty();
		}
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM documents WHERE po_id = ? AND status <> 'READY'", Integer.class, poId))
				.as("no sheet left PENDING for the message to name").isZero();
	}

	/**
	 * When no sheet can be made, the press is refused with KMS-400155, and the whole send rolls back: no
	 * notification, no trail event, no audit entry, and no sheet row left behind. Made to fail by refusing to
	 * queue the new sheet, the one lever a test has without replacing the renderer; it reaches the same refusal
	 * as a render that does not come out READY.
	 */
	@Test
	@DisplayName("when no sheet can be made, Send on WhatsApp answers 409 KMS-400155 and nothing is queued or changed")
	void noSheetCanBeMadeIsRefusedAtThePress() throws Exception {
		UUID poId = sentPo("PO-2026-0205");
		org.mockito.Mockito.doThrow(new org.quartz.SchedulerException("no worker"))
				.when(scheduler).scheduleJob(org.mockito.ArgumentMatchers.any(org.quartz.JobDetail.class),
						org.mockito.ArgumentMatchers.any(org.quartz.Trigger.class));

		mvc.perform(whatsapp(poId))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400155"));

		assertThat(admin.queryForObject("SELECT status FROM purchase_orders WHERE id = ?", String.class, poId)).isEqualTo("SENT");
		assertThat(admin.queryForObject("SELECT count(*) FROM notifications", Integer.class)).isZero();
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM po_events WHERE po_id = ? AND event_type = 'WHATSAPP_SENT'", Integer.class, poId)).isZero();
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = 'PO_WHATSAPP_SENT'", Integer.class)).isZero();
		assertThat(admin.queryForObject("SELECT count(*) FROM documents WHERE po_id = ?", Integer.class, poId)).isZero();
	}

	/**
	 * The whole path from the order to the wire, with nothing mocked but Meta: the order is sent through the
	 * endpoint, the notification it stored is handed to a WhatsApp adapter built around this context's own
	 * settings service and document service, and a local server plays Meta. The vendor's phone would receive
	 * exactly the translated sheet's bytes, as the header's document.
	 */
	@Test
	@DisplayName("a purchase order send uploads the chosen READY sheet, byte for byte, and sends it as the header's document")
	void thePurchaseOrderSendCarriesTheSheetAsItsHeader() throws Exception {
		admin.update("UPDATE vendors SET preferred_language = 'kn' WHERE id = ?", vendor);
		UUID poId = sentPo("PO-2026-0203");
		sheet(poId, 1, "en", "READY", pdf("english v1"));
		byte[] translated = pdf("kannada v2, the one the vendor gets");
		sheet(poId, 2, "kn", "READY", translated);
		admin.update("""
				INSERT INTO tenant_settings (tenant_id, whatsapp_phone_number_id, whatsapp_waba_id, whatsapp_webhook_token)
				VALUES (?, 'phone-t200', 'waba-t200', 'webhook-t200')
				""", tenant);
		secrets.put(tenant, TenantSecretStore.Kind.WHATSAPP_ACCESS_TOKEN, "token-t200");
		UUID notificationId = send(poId);
		Map<String, Object> params = storedParams(notificationId);

		List<byte[]> bodies = Collections.synchronizedList(new ArrayList<>());
		List<String> paths = Collections.synchronizedList(new ArrayList<>());
		metaServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		metaServer.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			paths.add(path);
			bodies.add(exchange.getRequestBody().readAllBytes());
			byte[] out = (path.endsWith("/media") ? "{\"id\":\"media-po-t200\"}"
					: "{\"messages\":[{\"id\":\"wamid.PO-T200\"}]}").getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, out.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(out);
			}
		});
		metaServer.start();
		WhatsAppChannelAdapter adapter = new WhatsAppChannelAdapter(whatsappSettings,
				new MetaWhatsAppClient(objectMapper, "http://127.0.0.1:" + metaServer.getAddress().getPort()), documents, "en");

		SendResult result;
		TenantContext.set(tenant);
		try {
			result = adapter.send("+919812345678", new OutboundMessage(NotificationTemplate.PO_DELIVERY, params,
					NotificationTemplate.PO_DELIVERY.render(params)));
		} finally {
			TenantContext.clear();
		}

		assertThat(result.sent()).as(String.valueOf(result.detail())).isTrue();
		assertThat(paths).containsExactly("/phone-t200/media", "/phone-t200/messages");
		assertThat(new String(bodies.get(0), StandardCharsets.ISO_8859_1))
				.as("the translated sheet's bytes, as stored").contains(new String(translated, StandardCharsets.ISO_8859_1));
		JsonNode header = objectMapper.readTree(bodies.get(1)).path("template").path("components").get(0);
		assertThat(header.path("type").asText()).isEqualTo("header");
		assertThat(header.path("parameters").get(0).path("type").asText()).isEqualTo("document");
		assertThat(header.path("parameters").get(0).path("document").path("id").asText()).isEqualTo("media-po-t200");
		assertThat(header.path("parameters").get(0).path("document").path("filename").asText()).isEqualTo("PO-2026-0203.pdf");
	}

	/**
	 * R-SL-3 and R-SL-1 on the WhatsApp text (T-260): what the message itself carries for a line
	 * ordered in packs.
	 *
	 * <p>The answer is no amount at all. The body is "Purchase order {no} for {vendor} is ready:
	 * {summary}. It was raised on {date}, and the items are needed by {date} at the latest.", and
	 * {@code summary} is the item names and a count, never a quantity. The amounts travel in the PDF in
	 * the message's header, which is the sheet {@code PurchaseOrderPackLineIT} reads "4 × Bag (25 Kg)"
	 * off. So there is nothing in the text to word as packs or to put in Kg, and this pins that: if a
	 * quantity is ever added to the summary, this fails and says the pack wording has to go with it.
	 */
	@Test
	@DisplayName("the WhatsApp text for a pack line names the item only; its amount travels in the attached sheet")
	void theWhatsAppTextCarriesNoAmountForAPackLine() throws Exception {
		UUID bag = admin.queryForObject("""
				INSERT INTO ingredient_pack_sizes (tenant_id, ingredient_id, name, quantity, unit)
				VALUES (?, ?, 'Bag', 25, 'KG') RETURNING id
				""", UUID.class, tenant, rice);
		UUID poId = admin.queryForObject("""
				INSERT INTO purchase_orders (tenant_id, po_number, vendor_id, status, sent_at, created_by)
				VALUES (?, 'PO-2026-0260', ?, 'SENT', now(), ?) RETURNING id
				""", UUID.class, tenant, vendor, staffId);
		admin.update("""
				INSERT INTO purchase_order_lines (tenant_id, po_id, ingredient_id, quantity, unit, expected_price,
						pack_size_id, pack_count)
				VALUES (?, ?, ?, 100, 'KG', 60, ?, 4)
				""", tenant, poId, rice, bag);
		UUID sheet = sheet(poId, 1, "en", "READY", pdf("pack sheet"));

		UUID notificationId = send(poId);
		Map<String, Object> params = storedParams(notificationId);

		assertThat(params.get("summary")).isEqualTo("1 item(s): Rice");
		assertThat(params.get("documentId")).as("the sheet with the amounts goes with it").isEqualTo(sheet.toString());
		// Every parameter the text is built from, and none of them is an amount. (Asserted on the keys
		// rather than by searching the stored JSON for "100": the document id is a uuid, and a uuid
		// may contain any three digits.)
		assertThat(params.keySet()).containsExactlyInAnyOrder(
				"poNumber", "vendor", "summary", "raised", "neededBy", "documentId");
	}

	/**
	 * T-311: the dates in the vendor's message are written the way every screen and the attached
	 * sheet write them. With no locale the formatter took the JVM default, US English, and wrote
	 * "20 Sep 2026" beside a sheet reading "20 Sept 2026". September is the only month where the two
	 * differ, so both dates are in September; March is there to show the other months are unchanged.
	 */
	@Test
	@DisplayName("the WhatsApp dates are written the screen's way: 20 Sept 2026, not the US 20 Sep 2026 (T-311)")
	void theWhatsAppDatesMatchTheScreen() throws Exception {
		UUID poId = admin.queryForObject("""
				INSERT INTO purchase_orders (tenant_id, po_number, vendor_id, status, sent_at, created_by,
						order_date, needed_by)
				VALUES (?, 'PO-2026-0311', ?, 'SENT', now(), ?, DATE '2026-09-20', DATE '2027-03-05') RETURNING id
				""", UUID.class, tenant, vendor, staffId);
		line(poId);
		sheet(poId, 1, "en", "READY", pdf("dated sheet"));

		Map<String, Object> params = storedParams(send(poId));

		assertThat(params.get("raised")).isEqualTo("20 Sept 2026");
		assertThat(params.get("neededBy")).isEqualTo("5 Mar 2027");
	}

	// ---------------------------------------------------------------------

	private UUID send(UUID poId) throws Exception {
		String body = mvc.perform(whatsapp(poId)).andExpect(status().isAccepted())
				.andReturn().getResponse().getContentAsString();
		return UUID.fromString(
				new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("notificationId").asText());
	}

	private void setProviderMessageId(UUID notificationId, String providerMessageId) {
		admin.update("UPDATE notifications SET provider_message_id = ? WHERE id = ?",
				providerMessageId, notificationId);
	}

	private UUID sentPo(String number) {
		UUID poId = admin.queryForObject("""
				INSERT INTO purchase_orders (tenant_id, po_number, vendor_id, status, sent_at, created_by)
				VALUES (?, ?, ?, 'SENT', now(), ?) RETURNING id
				""", UUID.class, tenant, number, vendor, staffId);
		line(poId);
		return poId;
	}

	/** An order as it is the moment it is raised: no sent_at, and nothing has been asked of the vendor. */
	private UUID draftPo(String number) {
		UUID poId = admin.queryForObject("""
				INSERT INTO purchase_orders (tenant_id, po_number, vendor_id, status, created_by)
				VALUES (?, ?, ?, 'DRAFT', ?) RETURNING id
				""", UUID.class, tenant, number, vendor, staffId);
		line(poId);
		return poId;
	}

	private void line(UUID poId) {
		admin.update("""
				INSERT INTO purchase_order_lines (tenant_id, po_id, ingredient_id, quantity, unit)
				VALUES (?, ?, ?, 30, 'KG')
				""", tenant, poId, rice);
	}

	private MockHttpServletRequestBuilder whatsapp(UUID poId) {
		return authed(post("/api/v1/purchase-orders/{id}/whatsapp", poId));
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}

}
