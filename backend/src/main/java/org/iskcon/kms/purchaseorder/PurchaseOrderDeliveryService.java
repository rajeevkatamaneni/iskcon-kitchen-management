package org.iskcon.kms.purchaseorder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.document.DocumentGenerationService;
import org.iskcon.kms.document.DocumentService;
import org.iskcon.kms.document.DisplayDates;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.notification.NotificationRecipient;
import org.iskcon.kms.notification.NotificationService;
import org.iskcon.kms.notification.NotificationTemplate;
import org.iskcon.kms.user.User.NotificationChannel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sending a purchase order to its vendor on WhatsApp (E5-S7) through the E1-S10 notification service —
 * ordering the way Indian vendors actually communicate. A draft is sent first (transitioned to SENT
 * with its sheet generated) as part of delivering it; a received or cancelled order can't go out.
 *
 * <p>The send is recorded on the PO's trail linked to the notification, so the provider's delivery
 * webhook can reflect the outcome back onto the trail and flag an unreachable vendor
 * ({@link PurchaseOrderDeliveryStatusListener}). A short rate guard stops an accidental send-loop
 * from spamming a vendor; a resend after that window is allowed and audited like the first.
 *
 * <p>A vendor with no phone number at all cannot be sent to, and says so (KMS-400130, T-025) before
 * the draft is touched. That is not a failure of the vendor record: since V101 a vendor need not
 * have a number, because the shop the temple walks into and pays at the counter has nothing to send
 * to and needs nothing to send to. The order is downloaded and handed over instead.
 */
@Service
public class PurchaseOrderDeliveryService {

	private static final int RATE_LIMIT_SECONDS = 120;
	private static final int SUMMARY_ITEMS = 3;

	private final JdbcTemplate jdbc;
	private final PurchaseOrderService purchaseOrders;
	private final NotificationService notificationService;
	private final AuditService auditService;
	private final DocumentService documentService;
	private final DocumentGenerationService documentGeneration;

	public PurchaseOrderDeliveryService(
			JdbcTemplate jdbc, PurchaseOrderService purchaseOrders,
			NotificationService notificationService, AuditService auditService,
			DocumentService documentService, DocumentGenerationService documentGeneration) {
		this.jdbc = jdbc;
		this.purchaseOrders = purchaseOrders;
		this.notificationService = notificationService;
		this.auditService = auditService;
		this.documentService = documentService;
		this.documentGeneration = documentGeneration;
	}

	/**
	 * Sends (or resends) the PO to its vendor on WhatsApp; returns the notification's id.
	 *
	 * <p><strong>Every refusal happens before anything changes.</strong> The three questions that can
	 * stop a send — is the order closed, has the vendor a number to send to, and is this a repeat
	 * inside the rate window — are all answered above the one statement that mutates, the DRAFT
	 * transition. The order they are asked in is not arbitrary either: the order's own state first,
	 * because a cancelled order cannot be sent to anybody; then the destination; then the guard
	 * against sending the same thing twice.
	 *
	 * <p>That shape is the T-025 fix and not tidying. The phone check used to be no check at all: the
	 * number was read after the transition and passed straight to the notification service, which
	 * built a recipient label by concatenation ("Vendor null") and then refused a moment later with a
	 * generic VALIDATION_FAILED whose text said "no contact address". The transaction rolled the
	 * transition back, so nothing was corrupted — but the person got a meaningless 400 for the
	 * entirely sensible situation of ordering from a shop they walk into, and the order they were
	 * looking at had, for the length of that transaction, been moved to SENT. Asking first means
	 * KMS-400130 says what is wrong and what to do instead, and the draft is never touched.
	 */
	@Transactional
	public UUID sendViaWhatsApp(AuthenticatedUser actor, UUID poId) {
		PurchaseOrderDetailView po = purchaseOrders.get(poId);
		PoStatus status = po.order().status();
		// CLOSED sits with the other two terminal states (T-142, D-26). Somebody has ended this
		// order, its remainder is back on the shopping list and may already have been re-ordered
		// elsewhere; sending the sheet again would ask the vendor for goods the temple has stopped
		// expecting from them.
		if (status == PoStatus.RECEIVED || status == PoStatus.CANCELLED
				|| status == PoStatus.CLOSED) {
			throw new ApplicationException(ErrorCode.PO_NOT_SENDABLE, Map.of("purchaseOrderId", poId));
		}

		Map<String, Object> vendor = jdbc.queryForMap(
				"SELECT name, phone, preferred_language FROM vendors WHERE id = ?", po.order().vendorId());
		String vendorName = (String) vendor.get("name");
		String phone = (String) vendor.get("phone");
		String vendorLanguage = (String) vendor.get("preferred_language");
		if (phone == null) {
			// A vendor with no number is not a broken record — it is the hardware shop somebody walks
			// into, and `vendors.phone` is nullable for exactly that (V101, T-025). There is nowhere
			// to send, and the answer is to hand the sheet over instead, which is what KMS-400130
			// says. Note this is NOT `vendors.whatsapp_reachable`, which is a stored flag about a
			// number that exists and bounced; a phoneless vendor reads `true` on it like any other.
			throw new ApplicationException(ErrorCode.VENDOR_HAS_NO_WHATSAPP_NUMBER,
					Map.of("purchaseOrderId", poId, "vendorId", po.order().vendorId()));
		}

		guardRate(poId);

		if (status == PoStatus.DRAFT) {
			// Sending a draft transitions it to SENT (and generates its sheet) as part of delivering.
			purchaseOrders.send(actor, poId);
			po = purchaseOrders.get(poId);
		}

		Map<String, Object> params = new HashMap<>();
		params.put("poNumber", po.order().poNumber());
		params.put("vendor", vendorName);
		params.put("summary", summarize(po.lines()));
		// The three dates, as parameters of their own rather than smuggled into the summary text.
		//
		// This changes the shape of the Meta template: `po_delivery` now takes five parameters and
		// has to be re-registered and approved before a send will succeed. That is a deployment step,
		// not a code one, and it is safe to take now because the product is pre-beta — Rajeev,
		// 2026-09-05: "We are still in dev adn test. Not even beta. So a change like this harms no
		// one." Once a temple is live it would not be, and the two-message migration would be the
		// only honest way to do it.
		params.put("raised", WHEN.format(po.order().orderDate()));
		params.put("neededBy", po.order().neededBy() == null
				? "no fixed date" : WHEN.format(po.order().neededBy()));
		// T-200: the sheet goes with the message as a PDF, so a message always names one. See sheetToSend.
		params.put("documentId", sheetToSend(poId, vendorLanguage).toString());

		UUID notificationId = notificationService.notify(
				NotificationRecipient.vendor(phone, null),
				NotificationTemplate.PO_DELIVERY, params, NotificationChannel.WHATSAPP);

		purchaseOrders.recordEvent(poId, "WHATSAPP_SENT",
				"Sent to " + vendorName + " on WhatsApp", actor, notificationId);
		auditService.record(actor, AuditAction.PO_WHATSAPP_SENT, AuditEntityType.PURCHASE_ORDER, poId,
				null, Map.of("poNumber", po.order().poNumber(), "channel", "WHATSAPP", "to", phone), null);

		return notificationId;
	}

	private void guardRate(UUID poId) {
		Integer recent = jdbc.queryForObject("""
				SELECT count(*) FROM po_events
				WHERE po_id = ? AND event_type = 'WHATSAPP_SENT'
				  AND created_at > now() - make_interval(secs => ?)
				""", Integer.class, poId, RATE_LIMIT_SECONDS);
		if (recent != null && recent > 0) {
			throw new ApplicationException(ErrorCode.PO_WHATSAPP_RATE_LIMITED, Map.of("purchaseOrderId", poId));
		}
	}

	/**
	 * The order sheet the vendor is sent on WhatsApp (T-200), as the id of a {@code documents} row.
	 *
	 * <p><strong>Which sheet, and why not simply the latest.</strong> A sheet is rendered in one language and
	 * stored with it, one row per version: the vendor's preferred language when nobody chose (E5-S1, which is
	 * how an order is translated), or a language picked on the order's screen for printing. So one order can
	 * hold a Kannada sheet for the vendor and, a version later, an English one somebody printed for the store
	 * room. The latest READY version would send the vendor the store room's English copy, which is what the
	 * lookup here did before. The brief for this: send the translated sheet if the order was translated,
	 * otherwise the English original. In order:
	 * <ol>
	 *   <li>the newest READY sheet in the vendor's preferred language: the translated sheet for a vendor who
	 *       reads another language, the English one for a vendor who reads English;</li>
	 *   <li>otherwise the newest READY English sheet, the original;</li>
	 *   <li>otherwise the newest READY sheet in any language, since a sheet the temple made is still the order;</li>
	 *   <li>otherwise no sheet is READY, and one is made now, while the person waits: see below.</li>
	 * </ol>
	 *
	 * <p><strong>Made now, not left to the worker, and why.</strong> A draft sent straight to WhatsApp has no
	 * READY sheet: sending it has only just asked for one, and the worker renders it in its own time. Naming
	 * that PENDING sheet and letting the message go meant the message job often ran before the sheet was
	 * READY, and the WhatsApp adapter, which never sends without the PDF, then failed it in the background
	 * with nothing said to the person who pressed the button. The brief was "generate one or refuse clearly".
	 * So the sheet is rendered here, synchronously, through the same {@link DocumentGenerationService#generate}
	 * the worker calls: the newest PENDING sheet, preferring the vendor's language, or a new one asked for in
	 * the vendor's language when there is none. The press takes as long as one render, which the order's own
	 * Generate PDF button already asks of the worker.
	 *
	 * <p><strong>Inside this transaction, and what that costs.</strong> The PENDING row the draft transition
	 * just inserted is not committed, so only this transaction can see it, and rendering it here is the only
	 * way to render it before the message is queued. {@code generate} is written to run outside a
	 * transaction, because a read that throws inside a surrounding one marks it rollback-only. Here that is
	 * exactly the wanted outcome: whenever the sheet does not come out READY, this throws
	 * {@link ErrorCode#PO_SHEET_NOT_READY} and the whole send rolls back, the DRAFT transition, the queued
	 * sheet and everything else with it. Nothing is changed or queued, and the person is told at the press.
	 * The worker's own job for the same row later finds it READY and does nothing, as it always has.
	 *
	 * <p>The WhatsApp adapter still refuses any sheet that is not READY when the message goes, as the second
	 * line of defence. A FAILED sheet is never chosen.
	 */
	private UUID sheetToSend(UUID poId, String vendorLanguage) {
		List<Sheet> sheets = sheetsOf(poId);
		String wanted = isEnglish(vendorLanguage) ? "en" : vendorLanguage.trim();
		java.util.Optional<UUID> ready = first(sheets, s -> s.ready() && sameLanguage(s.language(), wanted))
				.or(() -> first(sheets, s -> s.ready() && isEnglish(s.language())))
				.or(() -> first(sheets, Sheet::ready));
		if (ready.isPresent()) {
			return ready.get();
		}

		UUID toRender;
		try {
			toRender = first(sheets, s -> sameLanguage(s.language(), wanted))
					.or(() -> first(sheets, s -> true))
					.orElseGet(() -> documentService.requestPurchaseOrderPdf(poId, null));
			documentGeneration.generate(toRender);
		} catch (RuntimeException e) {
			// No scheduler to queue a sheet with, or a render that threw past generate's own catch. Either way
			// there is no PDF, and the answer is the same refusal.
			throw new ApplicationException(ErrorCode.PO_SHEET_NOT_READY, Map.of("purchaseOrderId", poId), e);
		}
		boolean readyNow = sheetsOf(poId).stream().anyMatch(s -> s.id().equals(toRender) && s.ready());
		if (!readyNow) {
			throw new ApplicationException(ErrorCode.PO_SHEET_NOT_READY,
					Map.of("purchaseOrderId", poId, "documentId", toRender));
		}
		return toRender;
	}

	private List<Sheet> sheetsOf(UUID poId) {
		return jdbc.query("""
				SELECT id, language, status FROM documents
				WHERE po_id = ? AND kind = 'PURCHASE_ORDER_PDF' AND status IN ('READY', 'PENDING')
				ORDER BY version DESC
				""", (rs, n) -> new Sheet(rs.getObject("id", UUID.class), rs.getString("language"),
						rs.getString("status")), poId);
	}

	private record Sheet(UUID id, String language, String status) {

		boolean ready() {
			return "READY".equals(status);
		}
	}

	private static java.util.Optional<UUID> first(List<Sheet> sheets, java.util.function.Predicate<Sheet> test) {
		return sheets.stream().filter(test).map(Sheet::id).findFirst();
	}

	/** As the sheet renderer reads a language: absent, blank or {@code en} is English. */
	private static boolean isEnglish(String language) {
		return language == null || language.isBlank() || "en".equalsIgnoreCase(language.trim());
	}

	private static boolean sameLanguage(String language, String wanted) {
		return isEnglish(language) ? isEnglish(wanted) : language.trim().equalsIgnoreCase(wanted);
	}

	/**
	 * The first few things on the order, in words, for a message a vendor actually reads.
	 *
	 * <p>{@link PurchaseOrderLineView#subject()} and never {@code ingredientName()}. Since T-024 a
	 * line may name a description instead of a catalogue ingredient, which makes
	 * {@code ingredientName()} null — and {@link Collectors#joining} appends a null element as the
	 * four literal characters "null" with no warning from the compiler and no failure at runtime. The
	 * result was a WhatsApp message to a real supplier reading "3 item(s): Rice, null, Sugar".
	 * {@code subject()} exists precisely so this is one call and cannot be got wrong again.
	 */
	private static String summarize(List<PurchaseOrderLineView> lines) {
		String names = lines.stream().limit(SUMMARY_ITEMS)
				.map(PurchaseOrderLineView::subject)
				.collect(Collectors.joining(", "));
		int extra = lines.size() - SUMMARY_ITEMS;
		String suffix = extra > 0 ? " and " + extra + " more" : "";

		return lines.size() + " item(s): " + names + suffix;
	}

	/**
	 * Short and unambiguous in a message that may be read on a small screen.
	 *
	 * <p>The same formatter as the attached PO sheet and every other document, {@link DisplayDates#DAY}
	 * (T-312), so the vendor's message reads "needed by 20 Sept 2026" beside a sheet and a screen that
	 * say the same. T-311 had pinned a private copy to British English; that copy is now the shared
	 * one. Only the parameter values are formatted here — the Meta template text around them is
	 * untouched, so nothing needs re-registering.
	 */
	private static final java.time.format.DateTimeFormatter WHEN = DisplayDates.DAY;
}
